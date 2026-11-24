package io.github.sargemck.practicalheadphones.bmap

/**
 * Capability and wire-quirk profile for a Bose BMAP headphone/earbud family, derived from
 * `bosectl` (`python/pybmap/devices/`).
 */
data class BoseProfile(
    val id: String,
    val displayName: String,
    val experimental: Boolean,
    val defaultChannel: Int,
    val requiresInitPacket: Boolean,
    val modeLayout: ModeLayout,
    val presetSlots: IntRange,
    val editableSlots: IntRange,
    val presetNames: List<String>,
    val supportsLiveAudioSettings: Boolean,
    val supportsAnr: Boolean,
    val supportsEq: Boolean,
    val supportsSelfVoice: Boolean,
    val supportsMultipoint: Boolean,
    val supportsAutoPlayPause: Boolean,
    val supportsAutoAnswer: Boolean,
    val supportsPairingMode: Boolean,
    val supportsPowerOff: Boolean,
    val multiComponentBattery: Boolean,
)

/**
 * Catalog of supported Bose BMAP models and heuristics to identify a model from its Bluetooth
 * name and/or live wire responses.
 */
object BoseCatalog {
    /** Init packet required by QC45 and QC35 before answering other GET requests. */
    val initPacket = Address(0, 1)

    /** QC35 / QC35 II Active Noise Reduction [1.6]. */
    val anr = ReadFeature(Address(1, 6), Parsers::anr)
    val writeAnr = WriteFeature(Address(1, 6), Builders::anr, Parsers::anr)

    /** Multi-component-aware battery reader on [2.2]. */
    val batteryStatus = ReadFeature(Address(2, 2), Parsers::batteryStatus)

    val QC_ULTRA_2 = BoseProfile(
        id = "qc_ultra2",
        displayName = "Bose QC Ultra 2",
        experimental = false,
        defaultChannel = 2,
        requiresInitPacket = false,
        modeLayout = ModeLayout.V48_40,
        presetSlots = QcUltra2.presetSlots,
        editableSlots = QcUltra2.editableSlots,
        presetNames = QcUltra2.presetNames,
        supportsLiveAudioSettings = true,
        supportsAnr = false,
        supportsEq = true,
        supportsSelfVoice = true,
        supportsMultipoint = true,
        supportsAutoPlayPause = true,
        supportsAutoAnswer = true,
        supportsPairingMode = true,
        supportsPowerOff = true,
        multiComponentBattery = false,
    )

    val QC_ULTRA_2_EARBUDS = BoseProfile(
        id = "qc_ultra2_earbuds",
        displayName = "Bose QC Ultra Earbuds 2",
        experimental = true,
        defaultChannel = 2,
        requiresInitPacket = false,
        modeLayout = ModeLayout.V48_40,
        presetSlots = 0..3,
        editableSlots = 4..10,
        presetNames = QcUltra2.presetNames,
        supportsLiveAudioSettings = true,
        supportsAnr = false,
        supportsEq = true,
        supportsSelfVoice = true,
        supportsMultipoint = true,
        supportsAutoPlayPause = true,
        supportsAutoAnswer = true,
        supportsPairingMode = true,
        supportsPowerOff = true,
        multiComponentBattery = true,
    )

    val QC_PRINCE = BoseProfile(
        id = "qc_prince",
        displayName = "Bose QuietComfort",
        experimental = true,
        defaultChannel = 8,
        requiresInitPacket = false,
        modeLayout = ModeLayout.V47_39,
        presetSlots = 0..1,
        editableSlots = 2..3,
        presetNames = listOf("Quiet", "Aware"),
        supportsLiveAudioSettings = false,
        supportsAnr = false,
        supportsEq = true,
        supportsSelfVoice = true,
        supportsMultipoint = true,
        supportsAutoPlayPause = false,
        supportsAutoAnswer = false,
        supportsPairingMode = true,
        supportsPowerOff = true,
        multiComponentBattery = false,
    )

    val QC_45 = BoseProfile(
        id = "qc45",
        displayName = "Bose QC45",
        experimental = true,
        defaultChannel = 8,
        requiresInitPacket = true,
        modeLayout = ModeLayout.V47_39,
        presetSlots = 0..1,
        editableSlots = 2..3,
        presetNames = listOf("Quiet", "Aware"),
        supportsLiveAudioSettings = false,
        supportsAnr = false,
        supportsEq = true,
        supportsSelfVoice = true,
        supportsMultipoint = true,
        supportsAutoPlayPause = false,
        supportsAutoAnswer = false,
        supportsPairingMode = true,
        supportsPowerOff = false,
        multiComponentBattery = false,
    )

    val QC_35 = BoseProfile(
        id = "qc35",
        displayName = "Bose QC35",
        experimental = true,
        defaultChannel = 8,
        requiresInitPacket = true,
        modeLayout = ModeLayout.NONE,
        presetSlots = IntRange.EMPTY,
        editableSlots = IntRange.EMPTY,
        presetNames = emptyList(),
        supportsLiveAudioSettings = false,
        supportsAnr = true,
        supportsEq = false,
        supportsSelfVoice = true,
        supportsMultipoint = false,
        supportsAutoPlayPause = false,
        supportsAutoAnswer = false,
        supportsPairingMode = true,
        supportsPowerOff = false,
        multiComponentBattery = false,
    )

    val ULTRA_OPEN = BoseProfile(
        id = "ultra_open",
        displayName = "Bose Ultra Open Earbuds",
        experimental = true,
        defaultChannel = 2,
        requiresInitPacket = false,
        modeLayout = ModeLayout.READ_ONLY_48,
        presetSlots = 0..1,
        editableSlots = IntRange.EMPTY,
        presetNames = listOf("Stereo", "Immersion"),
        supportsLiveAudioSettings = false,
        supportsAnr = false,
        supportsEq = true,
        supportsSelfVoice = false,
        supportsMultipoint = true,
        supportsAutoPlayPause = false,
        supportsAutoAnswer = false,
        supportsPairingMode = true,
        supportsPowerOff = true,
        multiComponentBattery = true,
    )

    val all: List<BoseProfile> = listOf(
        QC_ULTRA_2,
        QC_ULTRA_2_EARBUDS,
        QC_PRINCE,
        QC_45,
        QC_35,
        ULTRA_OPEN,
    )

    fun byId(id: String): BoseProfile? = all.firstOrNull { it.id == id }

    /** Best-effort model guess from the Bluetooth device name before any BMAP exchange. */
    fun inferFromName(btName: String?): BoseProfile? {
        val n = btName?.trim()?.lowercase() ?: return null
        return when {
            "ultra open" in n -> ULTRA_OPEN
            "ultra" in n && ("earbud" in n || "buds" in n) -> QC_ULTRA_2_EARBUDS
            "qc ultra" in n || "quietcomfort ultra" in n -> QC_ULTRA_2
            "qc45" in n || "quietcomfort 45" in n -> QC_45
            "qc35" in n || "quietcomfort 35" in n -> QC_35
            "quietcomfort" in n || "bose qc" in n -> QC_PRINCE
            else -> null
        }
    }

    /**
     * Refines the initial name-based guess using wire facts observed during connect/refresh.
     *
     * @param initial Profile guessed from Bluetooth name (or null).
     * @param batteryPayloadLength Length in bytes of the [2.2] battery STATUS payload, if read.
     * @param supportsModes Whether START [31.1] succeeded.
     * @param modeConfigSize Byte length of a [31.6] STATUS payload (47 or 48), if any arrived.
     * @param supportsLiveAudio Whether GET [31.10] succeeded.
     * @param supportsAnr Whether GET [1.6] succeeded.
     * @param usedInitPacket Whether GET [0.1] was needed to wake the BMAP session.
     */
    fun refineFromWire(
        initial: BoseProfile?,
        batteryPayloadLength: Int? = null,
        supportsModes: Boolean = true,
        modeConfigSize: Int? = null,
        supportsLiveAudio: Boolean = true,
        supportsAnr: Boolean = false,
        usedInitPacket: Boolean = false,
    ): BoseProfile {
        if (!supportsModes && supportsAnr) return QC_35
        if (initial == ULTRA_OPEN) return ULTRA_OPEN
        if (batteryPayloadLength != null && batteryPayloadLength >= 8) {
            return if (!supportsLiveAudio && initial == ULTRA_OPEN) ULTRA_OPEN else QC_ULTRA_2_EARBUDS
        }
        if (modeConfigSize == 47) {
            return when {
                initial == QC_45 || usedInitPacket -> QC_45
                initial == QC_PRINCE -> QC_PRINCE
                else -> QC_PRINCE
            }
        }
        if (modeConfigSize == 48 && !supportsLiveAudio) {
            return ULTRA_OPEN
        }
        return initial ?: QC_ULTRA_2
    }
}
