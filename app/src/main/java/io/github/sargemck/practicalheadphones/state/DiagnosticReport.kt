package io.github.sargemck.practicalheadphones.state

/**
 * Builds a privacy-safe Markdown diagnostic summary for bug/compatibility reports.
 *
 * Privacy invariants:
 * - Never includes [HeadphoneState.name] (user-chosen Bluetooth alias).
 * - Never includes custom mode names or [PairedHeadphoneChoice] addresses/names.
 * - Sanitizes [DeviceBadge.model] against a whitelist of known hardware model identifiers so a
 *   renamed headset (e.g. `"Alice's XM5"`) never leaks the user's name.
 */
object DiagnosticReport {
    // Return a catalog label, never text copied from a Bluetooth name.
    private val MODEL_LABELS = listOf(
        "QC Ultra Earbuds 2", "QC Ultra 2", "QC Ultra", "Ultra Open Earbuds",
        "QuietComfort 45", "QuietComfort 35 II", "QuietComfort 35", "QuietComfort",
        "QC45", "QC35 II", "QC35",
        "WH-1000XM3", "WH-1000XM4", "WH-1000XM5", "WH-1000XM6", "WF-1000XM4", "WF-1000XM5",
        "WH-CH720N", "LinkBuds S", "LinkBuds",
        "Life Q30", "Life Q35", "Space Q45", "Space One Pro", "Space One", "Space 2",
        "Liberty 4 NC", "Liberty 4", "Galaxy Buds2 Pro", "Galaxy Buds2", "Galaxy Buds FE",
        "Galaxy Buds Live", "Galaxy Buds Pro", "CMF Headphone Pro", "CMF Buds 2",
        "Nothing Ear (a)", "Nothing Ear (2)", "Nothing Ear", "Ear (a)", "Ear (2)", "Headphone (1)",
        "JBL Tune 230NC TWS", "JBL Wave Buds 2",
    )

    private val FIRMWARE_VERSION = Regex("""^\d+(?:\.\d+){1,3}(?:\+g[0-9a-f]{7,40})?$""")

    fun build(state: HeadphoneState, appVersion: String, sdkInt: Int): String {
        val active = if (state.link == LinkState.Connected) state else (state.lastKnown ?: state)
        val badge = active.deviceBadge
        val brand = badge?.brand ?: "Bose"
        val model = badge?.let { sanitizeModel(it.model) } ?: "QC Ultra 2 (verified baseline)"
        val tier = if (badge?.experimental == true) "experimental" else "verified"
        val caps = active.capabilities

        return buildString {
            appendLine("### Practical Headphones Diagnostic Report")
            appendLine("- App: $appVersion (Android SDK $sdkInt)")
            appendLine("- Link: ${state.link} (paired options: ${state.availableDevices.size})")
            appendLine("- Device: $brand · $model ($tier)")
            appendLine("- Firmware: ${describeSection(active.firmware) { if (FIRMWARE_VERSION.matches(it)) it else "present" }}")
            appendLine(
                "- Capabilities: rename=${caps.canRename}, pair=${caps.canPair}, " +
                    "powerOff=${caps.canPowerOff}, editModes=${caps.canEditModes}",
            )
            appendLine(
                "- Sections: " + listOf(
                    "battery=${describeSection(active.battery) { "ok(${state.batteryParts.size} parts)" }}",
                    "modes=${describeSection(active.modes) { "${it.modes.size} slots" }}",
                    "audio=${describeSection(active.audio) { "ok" }}",
                    "discreteAnc=${describeSection(active.discreteAnc) { "${it.options.size} opts, ambient=${it.ambientLevel != null}, voice=${it.focusOnVoice != null}" }}",
                    "eq=${describeSection(active.eq) { "${it.size} bands, presets=${active.eqPresets?.size ?: 0}" }}",
                    "selfVoice=${describeSection(active.selfVoice) { "ok" }}",
                    "speakToChat=${describeSection(active.speakToChat) { "ok" }}",
                    "autoPlayPause=${describeSection(active.autoPlayPause) { "ok" }}",
                    "autoAnswer=${describeSection(active.autoAnswer) { "ok" }}",
                    "multipoint=${describeSection(active.multipoint) { "ok" }}",
                ).joinToString(", "),
            )
        }.trimEnd()
    }

    internal fun sanitizeModel(raw: String): String =
        MODEL_LABELS.firstOrNull { raw.contains(it, ignoreCase = true) } ?: "unknown model"

    private fun <T> describeSection(section: Section<T>, onLoaded: (T) -> String): String = when (section) {
        Section.Loading -> "loading"
        Section.Unsupported -> "unsupported"
        is Section.Failed -> "failed"
        is Section.Loaded -> "loaded:${onLoaded(section.value)}"
    }
}
