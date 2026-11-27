package com.kmnexus.codexmeter.ui

import android.animation.ValueAnimator
import java.util.TimeZone
import org.robolectric.util.ReflectionHelpers

/**
 * Pins the machine-dependent pieces of golden screenshot captures, then restores them.
 *
 * Two host influences made the same fixtures render differently between machines (and between
 * runs on one machine):
 *
 * - **Timezone**: reset-time strings are formatted with `ZoneId.systemDefault()`. A golden
 *   recorded in UTC fails on a host in America/Los_Angeles because only the displayed hour
 *   changes. All captures run under UTC so the baseline travels.
 * - **Animators**: `rememberCodexMeterAnimatorsEnabled()` reads `ValueAnimator.areAnimatorsEnabled()`,
 *   which is true by default under Robolectric. With animations on, the infinite status-dot
 *   breathing halo keeps scheduling frames, so the capture either stalls for tens of seconds or
 *   lands on a random halo radius mid-cycle. Setting the animator duration scale to zero makes
 *   every animation inert for the capture duration, with zero production-code change. The setter
 *   is hidden from the compile SDK, so it goes through Robolectric's reflection helpers; the
 *   public `getDurationScale()` getter confirms the write landed. A scale of 0 is also what an
 *   accessibility user with animations removed sees, so the goldens exercise the static rendering
 *   path that production already ships.
 *
 * Both are JVM-global, so they are restored in a finally block to avoid leaking into other tests
 * in a shared task worker.
 */
internal fun pinGoldenEnvironment(block: () -> Unit) {
    val originalTimeZone = TimeZone.getDefault()
    val originalDurationScale = ValueAnimator.getDurationScale()
    setAnimatorDurationScale(0f)
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    try {
        block()
    } finally {
        TimeZone.setDefault(originalTimeZone)
        setAnimatorDurationScale(originalDurationScale)
    }
}

private fun setAnimatorDurationScale(scale: Float) {
    ReflectionHelpers.callStaticMethod<Void>(
        ValueAnimator::class.java,
        "setDurationScale",
        ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, scale),
    )
    check(ValueAnimator.getDurationScale() == scale) {
        "ValueAnimator duration scale did not apply: expected $scale, got ${ValueAnimator.getDurationScale()}"
    }
}
