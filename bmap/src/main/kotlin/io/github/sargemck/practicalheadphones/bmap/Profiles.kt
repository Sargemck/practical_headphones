package io.github.sargemck.practicalheadphones.bmap

/** Settings of a custom mode. [cnc] 0..10, inverted like [AudioSettings.cnc]. */
data class ProfileSettings(val cnc: Int, val spatial: Spatial, val wind: Boolean, val anc: Boolean)

sealed class ProfileError(message: String) : Exception(message) {
    class NoFreeSlot : ProfileError("all custom mode slots are in use")

    class InvalidName(val name: String, val problem: NameProblem) : ProfileError("invalid name \"$name\": ${problem.reason}")

    class NotFound(val name: String) : ProfileError("no mode named \"$name\"")

    class PresetLocked(val index: Int) : ProfileError("mode $index is a Bose preset and can't be changed")
}

/** Why a name can't be used for a custom mode. */
enum class NameProblem(val reason: String) {
    Blank("blank"),
    TooLong("longer than ${Builders.MAX_NAME_BYTES} UTF-8 bytes"),
    Reserved("reserved for empty slots"),
    Preset("same as a Bose preset"),
    Duplicate("another mode has this name"),
}

/**
 * Custom mode logic, ported from bosectl `connection.py` (`create_profile`, `update_profile`,
 * `delete_profile`, `_find_free_slot`, `_refuse_preset_name`, `_find_profile`). Pure: each
 * function takes the current modes and returns the [ModeConfigWrite] to send.
 */
object Profiles {
    private const val FREE_NAME = "None"

    /**
     * The first custom slot whose name is blank or `None`. The configured bit is ignored: bosectl
     * treats it as unreliable. (Fw 10.12.12 sets it on a write and clears it when the name is
     * set back to `None`, M4 device run.)
     */
    fun findFreeSlot(
        modes: Collection<ModeConfig>,
        editableSlots: IntRange = QcUltra2.editableSlots,
    ): Int? {
        val byIndex = modes.associateBy { it.index }
        return editableSlots.firstOrNull { slot ->
            val mode = byIndex[slot]
            mode == null || mode.name.isBlank() || mode.name.trim().equals(FREE_NAME, ignoreCase = true)
        }
    }

    /** By name, ignoring case, preferring an editable slot. */
    fun findProfile(name: String, modes: Collection<ModeConfig>): ModeConfig? {
        val matches = modes.sortedBy { it.index }.filter { it.name.equals(name, ignoreCase = true) }
        return matches.firstOrNull { it.editable } ?: matches.firstOrNull()
    }

    /**
     * Throws [ProfileError.InvalidName] unless [name] can be used for a custom mode. Checks the
     * trimmed name, which is what [create] and [update] write. A name another configured mode
     * already has is refused too (not in bosectl), except the mode in slot [except] (a rename).
     */
    fun validateName(
        name: String,
        modes: Collection<ModeConfig>,
        except: Int? = null,
        presetNames: List<String> = QcUltra2.presetNames,
    ) {
        nameProblem(name, modes, except, presetNames)?.let { throw ProfileError.InvalidName(name, it) }
    }

    /** What [validateName] would refuse [name] for, or null if it's fine. */
    fun nameProblem(
        name: String,
        modes: Collection<ModeConfig>,
        except: Int? = null,
        presetNames: List<String> = QcUltra2.presetNames,
    ): NameProblem? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> NameProblem.Blank
            trimmed.toByteArray(Charsets.UTF_8).size > Builders.MAX_NAME_BYTES -> NameProblem.TooLong
            trimmed.equals(FREE_NAME, ignoreCase = true) -> NameProblem.Reserved
            (presetNames + modes.filter { !it.editable }.map { it.name.trim() })
                .any { it.equals(trimmed, ignoreCase = true) } -> NameProblem.Preset
            modes.any { it.index != except && isConfigured(it) && it.name.trim().equals(trimmed, ignoreCase = true) } ->
                NameProblem.Duplicate
            else -> null
        }
    }

    /** A slot holding a mode, not the `None` sentinel or a blank name. */
    fun isConfigured(mode: ModeConfig): Boolean =
        mode.name.isNotBlank() && !mode.name.trim().equals(FREE_NAME, ignoreCase = true)

    fun create(
        modes: Collection<ModeConfig>,
        name: String,
        settings: ProfileSettings,
        editableSlots: IntRange = QcUltra2.editableSlots,
        presetNames: List<String> = QcUltra2.presetNames,
    ): ModeConfigWrite {
        validateName(name, modes, presetNames = presetNames)
        val slot = findFreeSlot(modes, editableSlots) ?: throw ProfileError.NoFreeSlot()
        return ModeConfigWrite(
            index = slot,
            promptB1 = 0,
            promptB2 = 0,
            name = name.trim(),
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
        presetSlots: IntRange = QcUltra2.presetSlots,
        presetNames: List<String> = QcUltra2.presetNames,
    ): ModeConfigWrite = update(modes, editable(name, modes, presetSlots), rename, settings, presetNames)

    /** [update] for the mode in slot [index]. */
    fun update(
        modes: Collection<ModeConfig>,
        index: Int,
        rename: String? = null,
        settings: ProfileSettings? = null,
        presetSlots: IntRange = QcUltra2.presetSlots,
        presetNames: List<String> = QcUltra2.presetNames,
    ): ModeConfigWrite = update(modes, editable(index, modes, presetSlots), rename, settings, presetNames)

    private fun update(
        modes: Collection<ModeConfig>,
        mode: ModeConfig,
        rename: String?,
        settings: ProfileSettings?,
        presetNames: List<String>,
    ): ModeConfigWrite {
        if (rename != null && !rename.trim().equals(mode.name.trim(), ignoreCase = true)) {
            validateName(rename, modes, except = mode.index, presetNames = presetNames)
        }
        return ModeConfigWrite(
            index = mode.index,
            promptB1 = mode.promptB1,
            promptB2 = mode.promptB2,
            name = rename?.trim() ?: mode.name,
            cnc = settings?.cnc ?: mode.cnc,
            spatial = settings?.spatial ?: mode.spatial,
            wind = settings?.wind ?: mode.wind,
            anc = settings?.anc ?: mode.anc,
        )
    }

    /**
     * Resets the slot to the `None` sentinel. Settings are what fw 10.12.12 holds in a factory
     * empty slot (cnc 10, spatial off, wind off, noise cancelling on; M4 device run), not
     * bosectl's zeros, so a deleted slot reads like one that was never used.
     */
    fun delete(
        modes: Collection<ModeConfig>,
        name: String,
        presetSlots: IntRange = QcUltra2.presetSlots,
    ): ModeConfigWrite = cleared(editable(name, modes, presetSlots).index)

    /** [delete] for the mode in slot [index]. */
    fun delete(
        modes: Collection<ModeConfig>,
        index: Int,
        presetSlots: IntRange = QcUltra2.presetSlots,
    ): ModeConfigWrite = cleared(editable(index, modes, presetSlots).index)

    private fun cleared(index: Int) = ModeConfigWrite(
        index = index,
        promptB1 = 0,
        promptB2 = 0,
        name = FREE_NAME,
        cnc = 10,
        spatial = Spatial.OFF,
        wind = false,
        anc = true,
    )

    private fun editable(name: String, modes: Collection<ModeConfig>, presetSlots: IntRange): ModeConfig =
        locked(findProfile(name, modes) ?: throw ProfileError.NotFound(name), presetSlots)

    private fun editable(index: Int, modes: Collection<ModeConfig>, presetSlots: IntRange): ModeConfig =
        locked(modes.firstOrNull { it.index == index && isConfigured(it) } ?: throw ProfileError.NotFound("slot $index"), presetSlots)

    private fun locked(mode: ModeConfig, presetSlots: IntRange): ModeConfig {
        if (!mode.editable || mode.index in presetSlots) throw ProfileError.PresetLocked(mode.index)
        return mode
    }
}
