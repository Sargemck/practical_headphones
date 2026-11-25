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
    private val KNOWN_MODEL_PATTERNS = listOf(
        Regex("""(?i)qc ultra earbuds\s*2?"""),
        Regex("""(?i)qc ultra(?:\s*2)?"""),
        Regex("""(?i)ultra open(?:\s*earbuds)?"""),
        Regex("""(?i)qc\s*45|quietcomfort\s*45"""),
        Regex("""(?i)qc\s*35(?:\s*ii)?|quietcomfort\s*35(?:\s*ii)?"""),
        Regex("""(?i)quietcomfort"""),
        Regex("""(?i)w[hf]-1000xm[3-6]"""),
        Regex("""(?i)wh-ch\d{3}n?"""),
        Regex("""(?i)linkbuds(?:\s*[a-z]+)?"""),
        Regex("""(?i)life\s*q3[05]"""),
        Regex("""(?i)space\s*(?:q45|one(?:\s*pro)?|2)"""),
        Regex("""(?i)liberty\s*4(?:\s*nc)?"""),
        Regex("""(?i)galaxy\s*buds(?:\s*2(?:\s*pro)?|\s*fe|\s*live|\s*pro)?"""),
        Regex("""(?i)cmf\s*(?:headphone\s*pro|buds\s*2(?:\s*plus|\s*pro)?)"""),
        Regex("""(?i)(?:nothing\s*)?ear\s*(?:\([a12]\)|\(stick\)|\(open\))?"""),
        Regex("""(?i)headphone\s*\(1\)"""),
        Regex("""(?i)jbl\s*(?:tune|live|wave|tour)[a-z0-9\s-]{0,16}"""),
    )

    private val MAC_REGEX = Regex("""([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}""")

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
            appendLine("- Firmware: ${describeSection(active.firmware) { redactMacs(it) }}")
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

    internal fun sanitizeModel(raw: String): String {
        for (pattern in KNOWN_MODEL_PATTERNS) {
            val match = pattern.find(raw)
            if (match != null) return match.value.trim()
        }
        return "<custom alias redacted>"
    }

    private fun redactMacs(text: String): String = MAC_REGEX.replace(text, "XX:XX:XX:XX:XX:XX")

    private fun <T> describeSection(section: Section<T>, onLoaded: (T) -> String): String = when (section) {
        Section.Loading -> "loading"
        Section.Unsupported -> "unsupported"
        is Section.Failed -> "failed(${redactMacs(section.message)})"
        is Section.Loaded -> "loaded:${onLoaded(section.value)}"
    }
}
