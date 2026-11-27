#!/usr/bin/env node
/**
 * control-codexmeter.mjs — agent-facing verification CLI for the CodexMeter debug build.
 *
 * Wraps adb + gradle into composable subcommands so a bot (or a human) can install the
 * debug APK, seed sanitized fixture scenarios, drive the UI, and pull evidence artifacts
 * into an isolated per-run directory — all without a real account or token.
 *
 * Every subcommand supports:
 *   --json            machine-readable output on stdout (logs go to stderr)
 *   --run-id <id>     isolate artifacts under artifacts/cm-control/<run-id>/
 *
 * Subcommands:
 *   doctor                              check ANDROID_HOME/adb/devices/APK/JDK/Gradle, print install guidance
 *   install-launch                      ./gradlew installDebug + launch MainActivity
 *   seed-fixture --scenario <name>      broadcast a fixture scenario into the debug app (or clean)
 *   snapshot [--out <file>]             uiautomator dump -> compact accessibility JSON tree
 *   tap --text <label> | --xy <x,y>     tap by visible text (via snapshot) or raw coordinates
 *   type <text>                         type text into the focused field
 *   back                                press BACK
 *   screenshot --name <tag>             pull a PNG screenshot into the run directory
 *   record --seconds <n> --name <tag>   record the screen (mp4) into the run directory
 *   logcat-safe [--lines <n>]           app logcat with credential redaction, saved + printed
 *   cleanup                             stop recording, clear fixture state, reset logcat
 *
 * Exit codes: 0 ok · 2 usage error · 3 environment missing · 4 device/adb failure · 5 app-side failure
 */
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, realpathSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.kmnexus.codexmeter.debug';
const MAIN_ACTIVITY = `${PACKAGE}/com.kmnexus.codexmeter.MainActivity`;
const FIXTURE_ACTION = 'com.kmnexus.codexmeter.debug.SEED_FIXTURE';
const SCENARIOS = ['antigravity_2x2_depleted', 'codex_cf_blocked', 'kimi_active', 'multi_account_race'];

const VALUE_FLAGS = new Set([
  '--run-id',
  '--scenario',
  '--text',
  '--xy',
  '--name',
  '--seconds',
  '--lines',
  '--out',
  '--mode',
  '--serial',
]);

// ---------------------------------------------------------------------------
// Small utilities
// ---------------------------------------------------------------------------

export class UsageError extends Error {
  constructor(message, hint = '', code = 2, json = false) {
    super(message);
    this.name = 'UsageError';
    this.hint = hint;
    this.code = code;
    this.json = json;
  }
}

function fail(message, hint, code = 2, json = false) {
  const payload = { ok: false, error: message, hint };
  if (json) {
    process.stdout.write(JSON.stringify(payload, null, 2) + '\n');
  } else {
    process.stderr.write(`✗ ${message}\n${hint ? `  → ${hint}\n` : ''}`);
  }
  process.exit(code);
}

function emit(data, json) {
  if (json) {
    process.stdout.write(JSON.stringify(data, null, 2) + '\n');
  } else if (typeof data === 'string') {
    process.stdout.write(data + '\n');
  } else {
    for (const [k, v] of Object.entries(data)) {
      process.stdout.write(`${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}\n`);
    }
  }
}

export function validateSafeSegment(flag, value, json = false) {
  if (
    typeof value !== 'string' ||
    value.trim().length === 0 ||
    value === '.' ||
    value === '..' ||
    value.includes('..') ||
    value.includes('/') ||
    value.includes('\\') ||
    value.includes('\0')
  ) {
    throw new UsageError(
      `${flag} 包含非法路径字符: ${String(value)}`,
      `${flag} 仅允许单级文件名/标识，不能包含 ..、/、\\ 或空白`,
      2,
      json,
    );
  }
  return value;
}

export function parseArgs(argv) {
  const json = argv.includes('--json');
  const args = { _: [], json };

  const takeValue = (flag, index) => {
    const next = argv[index + 1];
    if (next === undefined || next.startsWith('--')) {
      throw new UsageError(`${flag} 缺少参数值`, '用 --help 查看参数格式', 2, json);
    }
    return next;
  };

  const parsePositiveInt = (flag, raw) => {
    const trimmed = raw.trim();
    const num = Number(trimmed);
    if (!trimmed || !Number.isInteger(num) || num <= 0) {
      throw new UsageError(`${flag} 必须为正整数: ${raw}`, '例如 --seconds 10 或 --lines 500', 2, json);
    }
    return num;
  };

  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--json') args.json = true;
    else if (a === '--help' || a === '-h') args.help = true;
    else if (VALUE_FLAGS.has(a)) {
      const val = takeValue(a, i);
      i++;
      if (a === '--run-id') args.runId = validateSafeSegment(a, val, json);
      else if (a === '--scenario') args.scenario = val;
      else if (a === '--text') args.text = val;
      else if (a === '--xy') args.xy = val;
      else if (a === '--name') args.name = validateSafeSegment(a, val, json);
      else if (a === '--seconds') args.seconds = parsePositiveInt(a, val);
      else if (a === '--lines') args.lines = parsePositiveInt(a, val);
      else if (a === '--out') args.out = val;
      else if (a === '--mode') args.mode = val;
      else if (a === '--serial') args.serial = val;
    } else if (a.startsWith('--')) {
      throw new UsageError(`未知参数 ${a}`, '用 --help 查看支持参数', 2, json);
    } else {
      args._.push(a);
    }
  }
  return args;
}

export function repoRoot() {
  // scripts/ sits at the repo root; resolve relative to this file (spaces-safe via fileURLToPath).
  return resolve(fileURLToPath(new URL('..', import.meta.url)));
}

function runDir(args) {
  const id = args.runId || `run-${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}`;
  const dir = join(repoRoot(), 'artifacts', 'cm-control', id);
  mkdirSync(dir, { recursive: true });
  return dir;
}

function adbBin() {
  const home = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  const candidates = [
    home ? join(home, 'platform-tools', 'adb') : null,
    'adb', // PATH fallback
  ].filter(Boolean);
  for (const bin of candidates) {
    const r = spawnSync(bin, ['version'], { encoding: 'utf8' });
    if (!r.error && r.status === 0) return bin;
  }
  return null;
}

function adb(args, cmdArgs, { encoding = 'utf8', timeout = 30000 } = {}) {
  const bin = adbBin();
  if (!bin) {
    fail('找不到 adb', '安装 Android SDK Platform-Tools 并设置 ANDROID_HOME，或把 adb 加入 PATH。macOS: brew install --cask android-platform-tools', 3, args.json);
  }
  const full = args.serial ? ['-s', args.serial, ...cmdArgs] : cmdArgs;
  try {
    return execFileSync(bin, full, { encoding, timeout, maxBuffer: 64 * 1024 * 1024 });
  } catch (e) {
    const stderr = e.stderr ? String(e.stderr).trim() : '';
    fail(`adb 命令失败: adb ${full.join(' ')}`, stderr || '确认设备在线：adb devices；必要时重插或 adb kill-server && adb start-server', 4, args.json);
  }
}

function devices() {
  const bin = adbBin();
  if (!bin) return [];
  const out = execFileSync(bin, ['devices'], { encoding: 'utf8' });
  return out
    .split('\n')
    .slice(1)
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('*'))
    .map((l) => {
      const [serial, state] = l.split(/\s+/);
      return { serial, state };
    })
    .filter((d) => d.serial);
}

function requireDevice(args) {
  const list = devices().filter((d) => d.state === 'device');
  if (args.serial) {
    const hit = list.find((d) => d.serial === args.serial);
    if (!hit) fail(`设备 ${args.serial} 不在线`, 'adb devices 确认序列号；模拟器用 emulator -avd <name> 启动', 4, args.json);
    return hit;
  }
  if (list.length === 0) {
    fail('没有在线设备', '启动模拟器（Android Studio Device Manager 或 emulator -avd）或连接真机并授权 USB 调试，再 adb devices 确认', 4, args.json);
  }
  if (list.length > 1) {
    fail(`有 ${list.length} 台设备在线，未指定目标`, `用 --serial <id> 指定：${list.map((d) => d.serial).join(', ')}`, 2, args.json);
  }
  return list[0];
}

function pollFixtureMarker(args, scenario, nonce, timeoutMs = 20000) {
  const bin = adbBin();
  if (!bin) return { ok: false, error: 'adb-missing' };
  const started = Date.now();
  // The receiver echoes the nonce we passed; requiring it in the marker means we confirm
  // THIS execution and can never green-light on a stale marker from an earlier run.
  const okNeedle = `CMFIXTURE_OK scenario=${scenario}`;
  const errNeedle = `CMFIXTURE_ERR scenario=${scenario}`;
  const nonceNeedle = nonce ? ` nonce=${nonce}` : '';
  const serialArgs = args.serial ? ['-s', args.serial] : [];
  while (Date.now() - started < timeoutMs) {
    const res = spawnSync(
      bin,
      [...serialArgs, 'logcat', '-d', '-s', 'DebugFixtureReceiver:*', '-t', '200'],
      { encoding: 'utf8', timeout: 10000 },
    );
    if (res.status === 0 && res.stdout) {
      const lines = res.stdout.split('\n');
      const okLine = lines.find((l) => l.includes(okNeedle) && (!nonce || l.includes(nonceNeedle)));
      if (okLine) return { ok: true };
      const errLine = lines.find((l) => l.includes(errNeedle) && (!nonce || l.includes(nonceNeedle)));
      if (errLine) return { ok: false, error: errLine.trim() };
    }
    spawnSync('sleep', ['1']);
  }
  return { ok: false, error: 'timeout' };
}

function waitForFixtureMarker(args, scenario, nonce, timeoutMs = 20000) {
  // Ensure adb errors still surface loudly for seed-fixture.
  adb(args, ['logcat', '-d', '-s', 'DebugFixtureReceiver:*', '-t', '1'], { timeout: 10000 });
  return pollFixtureMarker(args, scenario, nonce, timeoutMs);
}

// ---------------------------------------------------------------------------
// Credential redaction — anything token/cookie-shaped must never leave the device raw.
// Use horizontal whitespace ([ \t]) so replacements never bleed across log lines.
// ---------------------------------------------------------------------------
const REDACTION_PATTERNS = [
  // 1. JWTs
  [/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}/g, '[REDACTED-JWT]'],

  // 2. OAuth / callback URLs carrying error/code/state/code_verifier/token/secret query strings
  [/([^\s?"'<>]*(?:auth|oauth|callback)[^\s?"'<>]*\?)(?=[^\s"'<>]*\berror=)[^\s"'<>]+/gi, '$1[REDACTED]'],
  [
    /([^\s?"'<>]+\?)(?=[^\s"'<>]*\b(?:code|state|code_verifier|codeVerifier|access_token|refresh_token|id_token|token|api_key|apiKey|client_secret|clientSecret)=)[^\s"'<>]+/gi,
    '$1[REDACTED]',
  ],

  // 3. JSON string-valued credential fields (string quotes avoid clobbering numeric HTTP `"code": 200`)
  [
    /("(?:(?:set-)?cookie|authorization|(?:(?:access|refresh|id)[_-]?)?token|(?:access|refresh|id)Token|code|code[_-]?verifier|codeVerifier|state|x-api-key|api[_-]?key|apiKey|client[_-]?secret|clientSecret)"[ \t]*:[ \t]*")[^"]*(")/gi,
    '$1[REDACTED]$2',
  ],

  // 4. Authorization / Proxy-Authorization headers (Bearer, Basic, Digest, etc.) + standalone Bearer
  [
    /\b((?:proxy-)?authorization[ \t]*[:=][ \t]*)(?:(Bearer|Basic|Token|Digest|ApiKey)[ \t]+)?[^\s,"'}\]]+/gi,
    (_m, head, scheme) => (scheme ? `${head}${scheme} [REDACTED]` : `${head}[REDACTED]`),
  ],
  [/\b(Bearer[ \t]+)[^\s,"'}\]]+/gi, '$1[REDACTED]'],

  // 5. Cookie / Set-Cookie headers (single-value, quoted, or multi-segment semicolon lists), stopping before trailing prose
  [/\b((?:set-)?cookie[ \t]*[:=][ \t]*)(["'])[^"']*\2/gi, '$1$2[REDACTED]$2'],
  [
    /\b((?:set-)?cookie[ \t]*[:=][ \t]*)[^\s;,"'}\]]+(?:[ \t]*;[ \t]*[^\s;,"'}\]]+)*;?/gi,
    '$1[REDACTED]',
  ],

  // 6. Auth code, API keys, client secrets, code_verifier, tokens, bare code=/state=
  [/\b(auth(?:orization)?[ \t]+code[ \t]*[:=][ \t]*)[^\s,"'}\]]+/gi, '$1[REDACTED]'],
  [
    /(?<![A-Za-z0-9_-])((?:x-api-key|api[_-]?key|apiKey|client[_-]?secret|clientSecret|code[_-]?verifier|codeVerifier|(?:(?:access|refresh|id)[_-]?)?token)[ \t]*[:=][ \t]*)(["'])[^"']*\2/gi,
    '$1$2[REDACTED]$2',
  ],
  [
    /(?<![A-Za-z0-9_-])((?:x-api-key|api[_-]?key|apiKey|client[_-]?secret|clientSecret|code[_-]?verifier|codeVerifier|(?:(?:access|refresh|id)[_-]?)?token)[ \t]*[:=][ \t]*)[^\s&,"';)}\]]+/gi,
    '$1[REDACTED]',
  ],
  [/(?<![A-Za-z0-9_-])((?:code|state)=)[^&\s"',;)}\]]+/gi, '$1[REDACTED]'],
  [/(?<![A-Za-z0-9_-])(auth[ \t]*[:=][ \t]*)[A-Za-z0-9._~+/=:%-]{12,}/gi, '$1[REDACTED]'],

  // 7. Known raw key/session prefixes
  [/sk-[A-Za-z0-9_-]{16,}/g, '[REDACTED-KEY]'],
  [/sess-[A-Za-z0-9_-]{16,}/g, '[REDACTED-SESSION]'],
];

export function redact(text) {
  let out = text;
  for (const [pattern, replacement] of REDACTION_PATTERNS) {
    out = out.replace(pattern, replacement);
  }
  return out;
}

export function filterLogcatLines(raw, pidSet) {
  if (!raw) return [];
  return raw
    .replace(/\r\n/g, '\n')
    .split('\n')
    .filter((line) => {
      const m = /^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+\d+\s+\w\s+([^:]+):/.exec(line);
      if (!m) return false;
      if (pidSet && pidSet.has(m[1])) return true;
      // Keep fixture markers even if the app process crashed or restarted mid-seed.
      return m[2].trim() === 'DebugFixtureReceiver';
    });
}

export function escapeAndroidInputText(text) {
  if (/[\r\n]/.test(text)) {
    throw new UsageError('type 不支持换行符', '请分段输入或使用 keyevent 发送回车', 2);
  }
  // Escape all Android sh -c metacharacters first, then map spaces to %s for `input text`.
  return text
    .replace(/([\\"'`$&;|<>()*?[\]#~!{}])/g, '\\$1')
    .replace(/ /g, '%s');
}

// ---------------------------------------------------------------------------
// uiautomator XML -> compact accessibility tree. No XML dependency: the dump
// format is flat <node .../> elements, so a small tokenizer is enough and keeps
// this script dependency-free.
// ---------------------------------------------------------------------------
function decodeXmlEntities(s) {
  return s
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&'); // amp must be last: it introduces the others
}

function parseAttrs(tag) {
  const attrs = {};
  const re = /([\w-]+)="([^"]*)"/g;
  let m;
  while ((m = re.exec(tag)) !== null) attrs[m[1]] = decodeXmlEntities(m[2]);
  return attrs;
}

function parseBounds(s) {
  const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(s || '');
  if (!m) return null;
  return { x1: +m[1], y1: +m[2], x2: +m[3], y2: +m[4] };
}

function xmlToTree(xml) {
  const nodes = [];
  const stack = [];
  const re = /<node\b[^>]*(?:\/>|>)|<\/node>/g;
  let m;
  while ((m = re.exec(xml)) !== null) {
    const tag = m[0];
    if (tag === '</node>') {
      stack.pop();
      continue;
    }
    const attrs = parseAttrs(tag);
    const node = {
      text: attrs.text || undefined,
      desc: attrs['content-desc'] || undefined,
      id: attrs['resource-id'] || undefined,
      cls: attrs.class ? attrs.class.split('.').pop() : undefined,
      bounds: parseBounds(attrs.bounds) || undefined,
      clickable: attrs.clickable === 'true' || undefined,
      children: [],
    };
    Object.keys(node).forEach((k) => node[k] === undefined && delete node[k]);
    if (stack.length) stack[stack.length - 1].children.push(node);
    else nodes.push(node);
    if (!tag.endsWith('/>')) stack.push(node);
  }
  const prune = (n) => {
    const kept = n.children
      .map(prune)
      .filter((c) => c.text || c.desc || (c.children && c.children.length));
    if (kept.length) n.children = kept;
    else delete n.children;
    return n;
  };
  return nodes.map(prune);
}

function* walkTree(node) {
  yield node;
  for (const c of node.children || []) yield* walkTree(c);
}

// ---------------------------------------------------------------------------
// Subcommands
// ---------------------------------------------------------------------------

function cmdDoctor(args) {
  const report = { ok: true, checks: {}, guidance: [] };
  const check = (name, ok, detail, guidance) => {
    report.checks[name] = { ok, detail };
    if (!ok) {
      report.ok = false;
      if (guidance) report.guidance.push(guidance);
    }
  };

  const home = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  check('android_home', Boolean(home), home || '未设置', 'export ANDROID_HOME=~/Library/Android/sdk（macOS 默认路径），并加进 shell rc');

  const bin = adbBin();
  check('adb', Boolean(bin), bin || '未找到', 'brew install --cask android-platform-tools（macOS）或安装 Android SDK Platform-Tools');

  if (bin) {
    const devs = devices();
    const online = devs.filter((d) => d.state === 'device');
    check('device_online', online.length > 0, online.length ? online.map((d) => d.serial).join(', ') : '无在线设备', '启动模拟器：emulator -list-avds 查看后 emulator -avd <name>；或真机开启 USB 调试');

    if (online.length) {
      const serial = args.serial || online[0].serial;
      const out = spawnSync(bin, ['-s', serial, 'shell', 'pm', 'list', 'packages', PACKAGE], { encoding: 'utf8' });
      const installed = (out.stdout || '').includes(PACKAGE);
      check('debug_apk_installed', installed, installed ? `${PACKAGE} 已安装` : '未安装', installed ? null : '运行: node scripts/control-codexmeter.mjs install-launch（会自动构建并安装）');
    }
  }

  const java = spawnSync('java', ['-version'], { encoding: 'utf8' });
  const javaVersion = (java.stderr || java.stdout || '').split('\n')[0] || '';
  check('jdk', !java.error, javaVersion || '未找到', '安装 JDK 21：brew install temurin@21 或 Android Studio 自带 jbr');

  const wrapperExists = existsSync(join(repoRoot(), 'gradlew'));
  check('gradle_wrapper', wrapperExists, wrapperExists ? 'gradlew 存在' : '缺失', '在仓库根目录运行本脚本（scripts/ 的上一级应有 gradlew）');

  const apkPath = join(repoRoot(), 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
  check('debug_apk_built', existsSync(apkPath), existsSync(apkPath) ? apkPath : '尚未构建', '运行 ./gradlew assembleDebug');

  emit(report.checks && args.json ? report : Object.fromEntries(Object.entries(report.checks).map(([k, v]) => [k, `${v.ok ? '✓' : '✗'} ${v.detail}`]).concat(report.guidance.length ? [['guidance', report.guidance.join(' | ')]] : [])), args.json);
  process.exit(report.ok ? 0 : 3);
}

function cmdInstallLaunch(args) {
  requireDevice(args);
  const gradlew = join(repoRoot(), 'gradlew');
  process.stderr.write('构建并安装 debug APK（可能需要几分钟）…\n');
  const build = spawnSync(gradlew, ['installDebug'], { cwd: repoRoot(), encoding: 'utf8', timeout: 600000 });
  if (build.status !== 0) {
    fail('installDebug 构建失败', (build.stderr || '').split('\n').filter((l) => l.includes('error') || l.includes('FAIL')).slice(0, 5).join('; ') || '完整日志见 gradle 输出，先跑 ./gradlew assembleDebug 排查', 5, args.json);
  }
  adb(args, ['shell', 'am', 'start', '-n', MAIN_ACTIVITY]);
  emit({ ok: true, action: 'install-launch', activity: MAIN_ACTIVITY }, args.json);
}

function cmdSeedFixture(args) {
  const mode = args.mode || 'seed';
  if (mode !== 'seed' && mode !== 'clean') {
    fail(`未知模式 ${mode}`, '只支持 seed 或 clean', 2, args.json);
  }
  const scenario = args.scenario;
  if (mode === 'seed') {
    if (!scenario) fail('缺少 --scenario <name>', `可选：${SCENARIOS.join(', ')}`, 2, args.json);
    if (!SCENARIOS.includes(scenario)) fail(`未知场景 ${scenario}`, `可选：${SCENARIOS.join(', ')}`, 2, args.json);
  }
  requireDevice(args);
  const pkg = adb(args, ['shell', 'pm', 'list', 'packages', PACKAGE]);
  if (!pkg.includes(PACKAGE)) {
    fail('debug 包未安装', '先运行: node scripts/control-codexmeter.mjs install-launch', 5, args.json);
  }
  const nonce = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  const modeExtra = mode === 'seed'
    ? ['--es', 'scenario', scenario, '--es', 'mode', 'seed', '--es', 'nonce', nonce]
    : ['--es', 'mode', 'clean', '--es', 'nonce', nonce];
  adb(args, ['shell', 'run-as', PACKAGE, 'am', 'broadcast', '--user', '0', '-a', FIXTURE_ACTION, '-p', PACKAGE, ...modeExtra]);
  const target = mode === 'seed' ? scenario : 'clean';
  const result = waitForFixtureMarker(args, target, nonce);
  if (!result.ok) {
    fail(`fixture 注入失败或未确认（${target}）`, `查看 logcat-safe 输出排查；确认 DebugFixtureReceiver 已编入 debug 构建。错误: ${result.error || '无'}`, 5, args.json);
  }
  adb(args, ['shell', 'am', 'force-stop', PACKAGE]);
  adb(args, ['shell', 'monkey', '-p', PACKAGE, '-c', 'android.intent.category.LAUNCHER', '1']);
  emit({ ok: true, action: 'seed-fixture', scenario: mode === 'seed' ? scenario : undefined, mode, note: 'app restarted to render injected state' }, args.json);
}

function cmdSnapshot(args) {
  requireDevice(args);
  adb(args, ['shell', 'uiautomator', 'dump', '/data/local/tmp/cm-ui.xml']);
  const xml = adb(args, ['shell', 'cat', '/data/local/tmp/cm-ui.xml']);
  if (!xml.includes('<hierarchy')) {
    fail('uiautomator dump 为空或失败', '确认前台是目标应用且屏幕未锁；部分设备需要先 adb shell input keyevent 82 解锁', 4, args.json);
  }
  const tree = xmlToTree(xml);
  const dir = runDir(args);
  const outFile = args.out || join(dir, `snapshot-${Date.now()}.json`);
  writeFileSync(outFile, JSON.stringify(tree, null, 2));
  emit(args.json ? { ok: true, path: outFile, nodes: tree } : { ok: true, path: outFile, preview: JSON.stringify(tree).slice(0, 400) }, args.json);
}

function cmdTap(args) {
  if (args.xy) {
    const [x, y] = args.xy.split(',').map(Number);
    if (!Number.isFinite(x) || !Number.isFinite(y)) fail('--xy 格式错误', '用 --xy x,y 例如 --xy 540,1200', 2, args.json);
    requireDevice(args);
    adb(args, ['shell', 'input', 'tap', String(x), String(y)]);
    emit({ ok: true, action: 'tap', xy: [x, y] }, args.json);
    return;
  }
  if (!args.text) fail('tap 需要 --text <label> 或 --xy <x,y>', '', 2, args.json);
  requireDevice(args);
  adb(args, ['shell', 'uiautomator', 'dump', '/data/local/tmp/cm-ui.xml']);
  const xml = adb(args, ['shell', 'cat', '/data/local/tmp/cm-ui.xml']);
  const tree = xmlToTree(xml);
  const needle = args.text.toLowerCase();
  let hit = null;
  for (const root of tree) {
    for (const node of walkTree(root)) {
      const hay = [node.text, node.desc].filter(Boolean).join(' ').toLowerCase();
      if (hay.includes(needle) && node.bounds) {
        hit = node;
        break;
      }
    }
    if (hit) break;
  }
  if (!hit) fail(`屏幕上找不到 "${args.text}"`, '先 snapshot 查看当前 UI 树；注意文本可能因语言环境不同', 5, args.json);
  const x = Math.round((hit.bounds.x1 + hit.bounds.x2) / 2);
  const y = Math.round((hit.bounds.y1 + hit.bounds.y2) / 2);
  adb(args, ['shell', 'input', 'tap', String(x), String(y)]);
  emit({ ok: true, action: 'tap', text: args.text, xy: [x, y], node: { text: hit.text, desc: hit.desc, id: hit.id } }, args.json);
}

function cmdType(args) {
  const text = args._.join(' ') || args.text;
  if (!text) fail('type 需要文本参数', '例如: type hello-world', 2, args.json);
  let escaped;
  try {
    escaped = escapeAndroidInputText(text);
  } catch (err) {
    if (err instanceof UsageError) {
      fail(err.message, err.hint, err.code, args.json);
    }
    throw err;
  }
  requireDevice(args);
  adb(args, ['shell', 'input', 'text', escaped]);
  emit({ ok: true, action: 'type', length: text.length }, args.json);
}

function cmdBack(args) {
  requireDevice(args);
  adb(args, ['shell', 'input', 'keyevent', '4']);
  emit({ ok: true, action: 'back' }, args.json);
}

function cmdScreenshot(args) {
  requireDevice(args);
  const dir = runDir(args);
  const name = args.name || `shot-${Date.now()}`;
  const out = join(dir, `${name}.png`);
  const buf = adb(args, ['exec-out', 'screencap', '-p'], { encoding: 'buffer' });
  if (!buf || buf.length < 100) fail('screencap 输出异常', '设备可能锁屏或未授权；先解锁再试', 4, args.json);
  writeFileSync(out, buf);
  emit({ ok: true, action: 'screenshot', path: out, bytes: buf.length }, args.json);
}

function cmdRecord(args) {
  const seconds = args.seconds !== undefined ? args.seconds : 10;
  if (!Number.isFinite(seconds) || seconds <= 0 || seconds > 180) {
    fail('--seconds 需在 1-180 之间', 'screenrecord 上限 180 秒', 2, args.json);
  }
  requireDevice(args);
  const bin = adbBin();
  if (!bin) {
    fail('找不到 adb', '安装 Android SDK Platform-Tools 并设置 ANDROID_HOME', 3, args.json);
  }
  const serialArgs = args.serial ? ['-s', args.serial] : [];
  const dir = runDir(args);
  const name = args.name || `rec-${Date.now()}`;
  const devicePath = `/data/local/tmp/cm-rec-${process.pid}.mp4`;
  const out = join(dir, `${name}.mp4`);
  process.stderr.write(`录屏 ${seconds}s…\n`);

  let recordError = null;
  try {
    const rec = spawnSync(
      bin,
      [...serialArgs, 'shell', 'screenrecord', '--time-limit', String(seconds), devicePath],
      { encoding: 'utf8', timeout: (seconds + 15) * 1000 },
    );
    if (rec.error || rec.status !== 0) {
      const detail = rec.error ? rec.error.message : (rec.stderr || '').trim() || `exit ${rec.status}`;
      recordError = {
        message: `screenrecord 执行失败 (${rec.status ?? 'err'})`,
        hint: detail || '部分模拟器不支持 screenrecord，可在真机或支持的系统镜像上重试',
      };
    } else {
      const pulled = spawnSync(bin, [...serialArgs, 'pull', devicePath, out], { encoding: 'utf8', timeout: 60000 });
      if (pulled.error || pulled.status !== 0 || !existsSync(out)) {
        const detail = pulled.error ? pulled.error.message : (pulled.stderr || '').trim();
        recordError = {
          message: '录屏文件拉取失败',
          hint: detail || '设备存储或 adb pull 传输问题；重试或检查设备剩余空间',
        };
      }
    }
  } finally {
    spawnSync(bin, [...serialArgs, 'shell', 'rm', '-f', devicePath], { encoding: 'utf8', timeout: 10000 });
  }

  if (recordError) {
    fail(recordError.message, recordError.hint, 4, args.json);
  }
  emit({ ok: true, action: 'record', path: out, seconds }, args.json);
}

function cmdLogcatSafe(args) {
  const lines = args.lines !== undefined ? args.lines : 500;
  requireDevice(args);
  const bin = adbBin();
  if (!bin) {
    fail('找不到 adb', '安装 Android SDK Platform-Tools 并设置 ANDROID_HOME', 3, args.json);
  }
  const serialArgs = args.serial ? ['-s', args.serial] : [];
  // Non-fatal pidof: if the app crashed or stopped (exit 1), keep running so DebugFixtureReceiver
  // logs and crash evidence still reach logcat-safe output.
  const pidRes = spawnSync(bin, [...serialArgs, 'shell', 'pidof', PACKAGE], { encoding: 'utf8', timeout: 10000 });
  const pidList = pidRes.status === 0
    ? (pidRes.stdout || '').trim().split(/\s+/).filter((p) => /^\d+$/.test(p))
    : [];
  const pidSet = new Set(pidList);

  const raw = adb(args, ['logcat', '-d', '-v', 'threadtime', '-t', String(lines)], { timeout: 15000 });
  const filtered = filterLogcatLines(raw, pidSet);
  const safe = redact(filtered.join('\n'));
  const dir = runDir(args);
  const out = join(dir, `logcat-${Date.now()}.log`);
  writeFileSync(out, safe);
  if (args.json) {
    emit(
      {
        ok: true,
        path: out,
        pid: pidList.length ? pidList.join(' ') : null,
        pids: pidList,
        lines: filtered.length,
      },
      args.json,
    );
  } else {
    process.stdout.write(safe.slice(-4000) + `\n---\nsaved: ${out}\n`);
  }
}

function cmdCleanup(args) {
  const report = { ok: true, steps: {} };
  const online = devices().filter((d) => d.state === 'device');
  if (!online.length) {
    report.steps.device = 'offline-skip';
    emit(report, args.json);
    return;
  }
  // Reject ambiguous multi-device or missing --serial instead of silently failing adb while reporting ok: true.
  requireDevice(args);

  const bin = adbBin();
  const serialArgs = args.serial ? ['-s', args.serial] : [];
  const run = (cmdArgs) => {
    const r = spawnSync(bin, [...serialArgs, ...cmdArgs], { encoding: 'utf8', timeout: 15000 });
    return { status: r.error ? -1 : r.status, stderr: (r.stderr || '').trim() };
  };

  const kill = run(['shell', 'pkill', '-f', 'screenrecord']);
  report.steps.screenrecord = kill.status === 0 ? 'killed' : kill.status === 1 ? 'none-running' : `error(${kill.status})`;

  const rm = run(['shell', 'sh', '-c', 'rm -f /data/local/tmp/cm-ui.xml /data/local/tmp/cm-rec-*.mp4']);
  report.steps.tempFiles = rm.status === 0 ? 'removed' : `error(${rm.status})`;

  const nonce = `clean-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  const bc = run([
    'shell',
    'run-as',
    PACKAGE,
    'am',
    'broadcast',
    '--user',
    '0',
    '-a',
    FIXTURE_ACTION,
    '-p',
    PACKAGE,
    '--es',
    'mode',
    'clean',
    '--es',
    'nonce',
    nonce,
  ]);
  if (bc.status !== 0) {
    report.steps.fixtures = 'clean-broadcast-failed';
  } else {
    const marker = pollFixtureMarker(args, 'clean', nonce, 10000);
    report.steps.fixtures = marker.ok ? 'cleaned' : `clean-broadcast-sent(${marker.error || 'unconfirmed'})`;
  }

  const stop = run(['shell', 'am', 'force-stop', PACKAGE]);
  report.steps.forceStop = stop.status === 0 ? 'stopped' : `error(${stop.status})`;

  emit(report, args.json);
}

// ---------------------------------------------------------------------------
// Entry
// ---------------------------------------------------------------------------

const HELP = `control-codexmeter.mjs — CodexMeter debug 验证 CLI

用法: node scripts/control-codexmeter.mjs <subcommand> [options]

子命令:
  doctor                          环境体检（ANDROID_HOME/adb/设备/APK/JDK/Gradle），缺失给安装指引
  install-launch                  构建安装 debug APK 并启动 MainActivity
  seed-fixture --scenario <name>  注入脱敏假数据场景: ${SCENARIOS.join(', ')}
                                  --mode clean 清除全部 fixture 状态
  snapshot [--out <file>]         uiautomator dump 转紧凑无障碍 JSON 树
  tap --text <label> | --xy x,y   按可见文本或坐标点击
  type <text>                     向当前焦点输入文本
  back                            按返回键
  screenshot --name <tag>         截图 PNG 到运行目录
  record --seconds <n> --name <t> 录屏 mp4 到运行目录（1-180s）
  logcat-safe [--lines <n>]       应用日志（内置凭据脱敏）落盘+输出
  cleanup                         终止录屏/清 fixture/清临时文件/force-stop

全局选项:
  --json          JSON 输出（日志走 stderr）
  --run-id <id>   产物隔离到 artifacts/cm-control/<id>/
  --serial <id>   多设备时指定目标

退出码: 0 成功 · 2 用法错 · 3 环境缺失 · 4 设备/adb 故障 · 5 应用侧失败`;

function main() {
  const argv = process.argv.slice(2);
  if (!argv.length || argv[0] === '--help' || argv[0] === '-h') {
    process.stdout.write(HELP + '\n');
    process.exit(0);
  }
  const cmd = argv[0];
  let args;
  try {
    args = parseArgs(argv.slice(1));
  } catch (err) {
    if (err instanceof UsageError) {
      fail(err.message, err.hint, err.code, err.json);
    }
    throw err;
  }
  if (args.help) {
    process.stdout.write(HELP + '\n');
    process.exit(0);
  }
  const commands = {
    doctor: cmdDoctor,
    'install-launch': cmdInstallLaunch,
    'seed-fixture': cmdSeedFixture,
    snapshot: cmdSnapshot,
    tap: cmdTap,
    type: cmdType,
    back: cmdBack,
    screenshot: cmdScreenshot,
    record: cmdRecord,
    'logcat-safe': cmdLogcatSafe,
    cleanup: cmdCleanup,
  };
  const fn = commands[cmd];
  if (!fn) fail(`未知子命令 ${cmd}`, `可选：${Object.keys(commands).join(', ')}；--help 看详情`, 2, args.json);
  fn(args);
}

function isDirectExecution() {
  if (!process.argv[1]) return false;
  try {
    return realpathSync(fileURLToPath(import.meta.url)) === realpathSync(process.argv[1]);
  } catch {
    return resolve(fileURLToPath(import.meta.url)) === resolve(process.argv[1]);
  }
}

if (isDirectExecution()) {
  main();
}
