package com.kmnexus.codexmeter.ui

import android.animation.ValueAnimator
import java.util.Locale
import java.util.TimeZone
import org.robolectric.util.ReflectionHelpers

/**
 * Pins the machine-dependent pieces of golden screenshot captures, then restores them:
 * - JVM default [Locale] (drives number/date formatters);
 * - JVM default [TimeZone] (`UTC`, drives reset-time strings);
 * - [ValueAnimator] duration scale (`0f`, freezes infinite breathing animations deterministically).
 */
internal fun pinGoldenEnvironment(
    locale: Locale = Locale.US,
    block: () -> Unit,
) {
    val originalLocale = Locale.getDefault()
    val originalTimeZone = TimeZone.getDefault()
    val originalDurationScale = ValueAnimator.getDurationScale()
    setAnimatorDurationScale(0f)
    Locale.setDefault(locale)
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    try {
        block()
    } finally {
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
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
