import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import {
  UsageError,
  escapeAndroidInputText,
  filterLogcatLines,
  parseArgs,
  redact,
  repoRoot,
  validateSafeSegment,
} from './control-codexmeter.mjs';

const CLI_PATH = join(repoRoot(), 'scripts', 'control-codexmeter.mjs');

test('redact blocks all 6 credential bypass categories while keeping trailing prose', () => {
  const cases = [
    {
      name: '1a single-value cookie=secret',
      input: '01-01 12:00:00.000  1234  1234 I OkHttp  : cookie=secret_session_value trailing_ok',
      forbidden: ['secret_session_value'],
      required: ['cookie=[REDACTED]', 'trailing_ok'],
    },
    {
      name: '1b raw Cookie header without key=value',
      input: '01-01 12:00:00.000  1234  1234 I OkHttp  : Cookie: raw_value trailing_ok',
      forbidden: ['raw_value'],
      required: ['Cookie: [REDACTED]', 'trailing_ok'],
    },
    {
      name: '2 Cookie header with flag prefix before kv',
      input: '01-01 12:00:00.000  1234  1234 I OkHttp  : Cookie: flag; sid=secret123 trailing_ok',
      forbidden: ['flag', 'secret123'],
      required: ['Cookie: [REDACTED]', 'trailing_ok'],
    },
    {
      name: '3a OAuth callback URL with code and state',
      input: 'redirect=https://console.anthropic.com/oauth/code/callback?code=ac_live_123456&state=xyz999 next_step',
      forbidden: ['ac_live_123456', 'xyz999'],
      required: ['https://console.anthropic.com/oauth/code/callback?[REDACTED]', 'next_step'],
    },
    {
      name: '3b standalone code/state/code_verifier and JSON code',
      input: 'code=ac_live_123 state=xyz999 code_verifier=pkce_secret_987 {"code":"auth_code_in_json","state":"st_1"} done',
      forbidden: ['ac_live_123', 'xyz999', 'pkce_secret_987', 'auth_code_in_json', 'st_1'],
      required: ['code=[REDACTED]', 'state=[REDACTED]', 'code_verifier=[REDACTED]', '"code":"[REDACTED]"', 'done'],
    },
    {
      name: '4 non-sk API keys and client_secret',
      input: 'x-api-key: raw_header_key_42 api_key=raw_snake_99 apiKey="camelKeyVal" client_secret=cs_non_sk_value ok_tail',
      forbidden: ['raw_header_key_42', 'raw_snake_99', 'camelKeyVal', 'cs_non_sk_value'],
      required: ['x-api-key: [REDACTED]', 'api_key=[REDACTED]', 'apiKey="', 'client_secret=[REDACTED]', 'ok_tail'],
    },
    {
      name: '5 Authorization Basic header',
      input: 'Authorization: Basic dXNlcjpzZWNyZXRwYXNz trailing_ok',
      forbidden: ['dXNlcjpzZWNyZXRwYXNz'],
      required: ['Authorization: Basic [REDACTED]', 'trailing_ok'],
    },
    {
      name: '6 URL-encoded Bearer and token containing % and :',
      input: 'Authorization: Bearer user_01ABC%3A%3AsecretTokenValue token=user_01ABC%3A%3AsecretTokenValue done',
      forbidden: ['user_01ABC%3A%3AsecretTokenValue', 'secretTokenValue'],
      required: ['Authorization: Bearer [REDACTED]', 'token=[REDACTED]', 'done'],
    },
  ];

  for (const tc of cases) {
    const out = redact(tc.input);
    for (const bad of tc.forbidden) {
      assert.equal(out.includes(bad), false, `${tc.name}: leaked "${bad}" in "${out}"`);
    }
    for (const good of tc.required) {
      assert.equal(out.includes(good), true, `${tc.name}: expected "${good}" in "${out}"`);
    }
  }
});

test('redact preserves benign operational logs and multiline boundaries', () => {
  const safe = [
    '01-01 12:00:00.000  1234  1234 I DebugFixtureReceiver: CMFIXTURE_OK scenario=kimi_active mode=seed nonce=abc',
    '01-01 12:00:00.100  1234  1234 I Http: status_code=200 {"code": 200} Basic layout loaded',
    '01-01 12:00:00.200  1234  1234 I State: window_state=active error_code=401',
  ].join('\n');
  assert.equal(redact(safe), safe);
});

test('filterLogcatLines handles empty input, multi-PID sets, and DebugFixtureReceiver fallback', () => {
  assert.deepEqual(filterLogcatLines('', new Set(['100'])), []);

  const raw = [
    '--------- beginning of main',
    '01-01 12:00:00.001  1001  1001 I AppMain : main process line',
    '01-01 12:00:00.002  2002  2005 I Chromium: webview child process line',
    '01-01 12:00:00.003  9999  9999 I OtherApp: ignore pid 1001 in message',
    '01-01 12:00:00.004  8888  8888 I DebugFixtureReceiver: CMFIXTURE_OK scenario=clean nonce=n1',
  ].join('\n');

  const multi = filterLogcatLines(raw, new Set(['1001', '2002']));
  assert.equal(multi.length, 3);
  assert.equal(multi.some((l) => l.includes('OtherApp')), false);

  const crashed = filterLogcatLines(raw, new Set());
  assert.equal(crashed.length, 1);
  assert.match(crashed[0], /DebugFixtureReceiver/);
});

test('escapeAndroidInputText escapes Android sh -c metacharacters and rejects newlines', () => {
  const escaped = escapeAndroidInputText("a b(c)*?[d]#~!{e}&<>|;'\"$`\\");
  assert.equal(escaped.includes(' '), false);
  for (const ch of ['(', ')', '*', '?', '[', ']', '#', '~', '!', '{', '}', '&', '<', '>', '|', ';', "'", '"', '$', '`']) {
    assert.equal(escaped.includes(`\\${ch}`), true, `missing escape for ${ch}`);
  }
  assert.equal(escaped.includes('a%sb'), true);
  assert.throws(() => escapeAndroidInputText('line1\nline2'), UsageError);
  assert.throws(() => escapeAndroidInputText('line1\rline2'), UsageError);
});

test('parseArgs validates flags, missing values, safe segments, and numeric bounds', () => {
  const parsed = parseArgs(['--json', '--run-id', 'run_01', '--seconds', '15', '--lines', '200', '--help']);
  assert.equal(parsed.json, true);
  assert.equal(parsed.runId, 'run_01');
  assert.equal(parsed.seconds, 15);
  assert.equal(parsed.lines, 200);
  assert.equal(parsed.help, true);

  assert.throws(() => parseArgs(['--scenario']), UsageError);
  assert.throws(() => parseArgs(['--scenario', '--json']), UsageError);
  assert.throws(() => parseArgs(['--unknown', '--json']), (err) => err instanceof UsageError && err.json === true);
  assert.throws(() => parseArgs(['--seconds', '0']), UsageError);
  assert.throws(() => parseArgs(['--seconds', 'abc']), UsageError);
  assert.throws(() => parseArgs(['--lines', '-5']), UsageError);

  for (const bad of ['..', '../x', 'a/b', 'a\\b', '.', '', '   ']) {
    assert.throws(() => validateSafeSegment('--run-id', bad), UsageError);
  }
});

test('repoRoot resolves cleanly via fileURLToPath', () => {
  assert.equal(existsSync(CLI_PATH), true);
});

test('CLI handles subcommand --help and early validation without a device', () => {
  const helpRes = spawnSync(process.execPath, [CLI_PATH, 'record', '--help'], { encoding: 'utf8' });
  assert.equal(helpRes.status, 0);
  assert.match(helpRes.stdout, /control-codexmeter\.mjs/);

  const badFlagJson = spawnSync(process.execPath, [CLI_PATH, 'doctor', '--bogus', '--json'], { encoding: 'utf8' });
  assert.equal(badFlagJson.status, 2);
  const payload = JSON.parse(badFlagJson.stdout);
  assert.equal(payload.ok, false);
  assert.match(payload.error, /--bogus/);

  const typeEmpty = spawnSync(process.execPath, [CLI_PATH, 'type', '--json'], { encoding: 'utf8' });
  assert.equal(typeEmpty.status, 2);
  assert.equal(JSON.parse(typeEmpty.stdout).ok, false);

  const typeNewline = spawnSync(process.execPath, [CLI_PATH, 'type', 'hi\nthere', '--json'], { encoding: 'utf8' });
  assert.equal(typeNewline.status, 2);
  assert.equal(JSON.parse(typeNewline.stdout).ok, false);

  for (const badSec of ['0', '181', 'notanumber']) {
    const recBad = spawnSync(process.execPath, [CLI_PATH, 'record', '--seconds', badSec, '--json'], { encoding: 'utf8' });
    assert.equal(recBad.status, 2);
    assert.equal(JSON.parse(recBad.stdout).ok, false);
  }
});
