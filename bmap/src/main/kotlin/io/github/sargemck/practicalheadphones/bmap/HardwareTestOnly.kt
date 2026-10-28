package io.github.sargemck.practicalheadphones.bmap

/** Marks writes that only the hardware test may send, so app code can't call them by accident. */
@RequiresOptIn(message = "Only for the hardware test. App code must not send this.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class HardwareTestOnly
