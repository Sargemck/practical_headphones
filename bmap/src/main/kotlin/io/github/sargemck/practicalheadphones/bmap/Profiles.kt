package io.github.sargemck.practicalheadphones.bmap

/** Settings of a custom mode. [cnc] 0..10, inverted like [AudioSettings.cnc]. */
data class ProfileSettings(val cnc: Int, val spatial: Spatial, val wind: Boolean, val anc: Boolean)

sealed class ProfileError(message: String) : Exception(message) {
    class NoFreeSlot : ProfileError("all custom mode slots are in use")

    class InvalidName(val name: String, val reason: String) : ProfileError("invalid name \"$name\": $reason")

    class NotFound(val name: String) : ProfileError("no mode named \"$name\"")

    class PresetLocked(val index: Int) : ProfileError("mode $index is a Bose preset and can't be changed")
}

/**
 * Custom mode logic, ported from bosectl `connection.py` (`create_profile`, `update_profile`,
 * `delete_profile`, `_find_free_slot`, `_refuse_preset_name`, `_find_profile`). Pure: each
 * function takes the current modes and returns the [ModeConfigWrite] to send.
 */
object Profiles {
    private const val FREE_NAME = "None"

    /**
     * The first custom slot whose name is blank or `None`. The configured bit is ignored:
     * the firmware sets it on first write and never clears it.
     */
    fun findFreeSlot(modes: Collection<ModeConfig>): Int? {
        val byIndex = modes.associateBy { it.index }
        return QcUltra2.editableSlots.firstOrNull { slot ->
            val mode = byIndex[slot]
            mode == null || mode.name.isBlank() || mode.name.trim().equals(FREE_NAME, ignoreCase = true)
        }
    }

    /** By name, ignoring case, preferring an editable slot. */
    fun findProfile(name: String, modes: Collection<ModeConfig>): ModeConfig? {
        val matches = modes.sortedBy { it.index }.filter { it.name.equals(name, ignoreCase = true) }
        return matches.firstOrNull { it.editable } ?: matches.firstOrNull()
    }

    /** Throws [ProfileError.InvalidName] unless [name] can be used for a custom mode. */
    fun validateName(name: String, modes: Collection<ModeConfig>) {
        val trimmed = name.trim()
        fun invalid(reason: String): Nothing = throw ProfileError.InvalidName(name, reason)
        if (trimmed.isEmpty()) invalid("blank")
        if (name.toByteArray(Charsets.UTF_8).size > Builders.MAX_NAME_BYTES) {
            invalid("longer than ${Builders.MAX_NAME_BYTES} UTF-8 bytes")
        }
        if (trimmed.equals(FREE_NAME, ignoreCase = true)) invalid("reserved for empty slots")
        val presets = QcUltra2.presetNames + modes.filter { !it.editable }.map { it.name.trim() }
        if (presets.any { it.equals(trimmed, ignoreCase = true) }) invalid("same as a Bose preset")
    }

    fun create(modes: Collection<ModeConfig>, name: String, settings: ProfileSettings): ModeConfigWrite {
        validateName(name, modes)
        val slot = findFreeSlot(modes) ?: throw ProfileError.NoFreeSlot()
        return ModeConfigWrite(
            index = slot,
            promptB1 = 0,
            promptB2 = 0,
            name = name,
            cnc = settings.cnc,
            spatial = settings.spatial,
            wind = settings.wind,
            anc = settings.anc,
        )
    }

    /** Changes [settings] and/or renames to [rename]. Keeps the voice prompt bytes. */
    fun update(
        modes: Collection<ModeConfig>,
        name: String,
        rename: String? = null,
        settings: ProfileSettings? = null,
    ): ModeConfigWrite {
        val mode = editable(name, modes)
        if (rename != null && !rename.trim().equals(mode.name.trim(), ignoreCase = true)) {
            validateName(rename, modes)
        }
        return ModeConfigWrite(
            index = mode.index,
            promptB1 = mode.promptB1,
            promptB2 = mode.promptB2,
            name = rename ?: mode.name,
            cnc = settings?.cnc ?: mode.cnc,
            spatial = settings?.spatial ?: mode.spatial,
            wind = settings?.wind ?: mode.wind,
            anc = settings?.anc ?: mode.anc,
        )
    }

    /** Resets the slot to the `None` sentinel with zeroed settings, as bosectl does. */
    fun delete(modes: Collection<ModeConfig>, name: String): ModeConfigWrite {
        val mode = editable(name, modes)
        return ModeConfigWrite(
            index = mode.index,
            promptB1 = 0,
            promptB2 = 0,
            name = FREE_NAME,
            cnc = 0,
            spatial = Spatial.OFF,
            wind = false,
            anc = false,
        )
    }

    private fun editable(name: String, modes: Collection<ModeConfig>): ModeConfig {
        val mode = findProfile(name, modes) ?: throw ProfileError.NotFound(name)
        if (!mode.editable || mode.index in QcUltra2.presetSlots) throw ProfileError.PresetLocked(mode.index)
        return mode
    }
}
