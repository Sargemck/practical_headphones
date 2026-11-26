package io.github.sargemck.practicalheadphones.state

/**
 * Builds a privacy-safe Markdown diagnostic summary for bug/compatibility reports.
 *
 * Privacy invariants:
 * - Never includes [HeadphoneState.name] (user-chosen Bluetooth alias).
 * - Never includes custom mode names or [PairedHeadphoneChoice] addresses/names.
 * - Sanitizes [DeviceBadge.model] against a whitelist of known hardware model identifiers so a
 *   renamed headset (e.g. `"Alice's XM5"`) never leaks the user's name.
 * - Firmware: keeps only the leading version number (e.g. `10.12.12` from `10.12.12-AA:BB:...`,
 *   optionally with a `+g<hash>` suffix) and drops everything after it. No leading version → `present`.
 * - Failures ([Section.Failed]) go through [sanitizeFailure]:
 *   - an exact match of a fixed driver string (`read failed`, `Couldn't read battery`, ...) is kept;
 *   - a Bose [io.github.sargemck.practicalheadphones.bmap.BmapException] message keeps only the
 *     `[fblock.func]` address and the error type (`ERROR <name|code N>`, `no reply`, `malformed payload`, ...).
 *     Payload hex and detail text are dropped, because a failed name read carries the name hex-encoded there;
 *   - anything else becomes plain `failed`.
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

    private val FIRMWARE_VERSION = Regex("""^\d+(?:\.\d+){1,3}(?:\+g[0-9a-f]{7,40})?""")

    // Every literal passed to Section.Failed(...) in app/src/main. Keep in sync with the drivers.
    private val FIXED_FAILURES = setOf(
        "read failed",
        "Couldn't read battery",
        "Couldn't read earbuds status",
        "Couldn't read listening mode",
    )

    // Bose: BmapException messages (bmap/.../BmapException.kt). Address + error type only.
    private val BMAP_FAILURE = Regex(
        """^\[\d{1,3}\.\d{1,3}\] (?:ERROR (?:code \d{1,3}|[A-Za-z]{1,24}(?:\(auth\))?)|no reply while the link answers: unsupported|no reply|malformed payload)""",
    )

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
            appendLine("- Firmware: ${describeSection(active.firmware) { sanitizeFirmware(it) }}")
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

    internal fun sanitizeFirmware(raw: String): String =
        FIRMWARE_VERSION.find(raw.trim())?.value ?: "present"

    internal fun sanitizeFailure(message: String): String {
        if (message in FIXED_FAILURES) return "failed($message)"
        BMAP_FAILURE.find(message)?.let { return "failed(${it.value})" }
        return "failed"
    }

    private fun <T> describeSection(section: Section<T>, onLoaded: (T) -> String): String = when (section) {
        Section.Loading -> "loading"
        Section.Unsupported -> "unsupported"
        is Section.Failed -> sanitizeFailure(section.message)
        is Section.Loaded -> "loaded:${onLoaded(section.value)}"
    }
}
