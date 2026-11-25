package io.github.sargemck.practicalheadphones.state

import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.EqBand
import io.github.sargemck.practicalheadphones.bmap.Favourites
import io.github.sargemck.practicalheadphones.bmap.ModeConfig
import io.github.sargemck.practicalheadphones.bmap.Multipoint
import io.github.sargemck.practicalheadphones.bmap.SelfVoice
import io.github.sargemck.practicalheadphones.bmap.SelfVoiceLevel
import io.github.sargemck.practicalheadphones.bmap.Spatial
import java.io.File
import java.util.concurrent.Executors

/** Keeps the last loaded settings across process restarts, so the UI has something to show while disconnected. */
interface LastKnownStore {
    fun load(): HeadphoneState?

    fun save(snapshot: HeadphoneState)

    object None : LastKnownStore {
        override fun load(): HeadphoneState? = null

        override fun save(snapshot: HeadphoneState) = Unit
    }
}

/** One small text file, written off the caller's thread and replaced atomically. */
class FileLastKnownStore(private val file: File, private val log: (String) -> Unit = {}) : LastKnownStore {
    private val writer = Executors.newSingleThreadExecutor()

    override fun load(): HeadphoneState? =
        runCatching { if (file.exists()) LastKnownCodec.decode(file.readText()) else null }
            .onFailure { log("LASTKNOWN load failed: $it") }
            .getOrNull()

    override fun save(snapshot: HeadphoneState) {
        val text = LastKnownCodec.encode(snapshot)
        writer.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { log("LASTKNOWN save failed: $it") }
        }
    }
}

/**
 * Line-based `key=value` text. Only [Section.Loaded] sections are written; everything else
 * reads back as [Section.Loading]. Battery is left out on purpose: an old level would mislead.
 * Unknown versions or malformed files read back as null.
 */
object LastKnownCodec {
    private const val VERSION = "3"
    private const val SEP = "|"

    fun encode(state: HeadphoneState): String = buildList {
        add("v=$VERSION")
        state.deviceBadge?.let { add("badge=${cleanField(it.brand)}$SEP${cleanField(it.model)}$SEP${bit(it.experimental)}") }
        val caps = state.capabilities
        if (caps != Capabilities()) {
            add(
                "caps=" + listOf(
                    bit(caps.canRename),
                    bit(caps.canPair),
                    bit(caps.canPowerOff),
                    bit(caps.canEditModes),
                    bit(caps.supportsModeAncToggle),
                    encodeRange(caps.editableSlots),
                    encodeRange(caps.presetSlots),
                    caps.presetNames.joinToString(",") { cleanField(it).replace(',', ' ') },
                ).joinToString(SEP),
            )
        }
        val unsupported = buildList {
            if (state.name is Section.Unsupported) add("name")
            if (state.firmware is Section.Unsupported) add("firmware")
            if (state.modes is Section.Unsupported) add("modes")
            if (state.audio is Section.Unsupported) add("audio")
            if (state.discreteAnc is Section.Unsupported) add("discreteAnc")
            if (state.eq is Section.Unsupported) add("eq")
            if (state.selfVoice is Section.Unsupported) add("selfVoice")
            if (state.speakToChat is Section.Unsupported) add("speakToChat")
            if (state.autoPlayPause is Section.Unsupported) add("autoPlayPause")
            if (state.autoAnswer is Section.Unsupported) add("autoAnswer")
            if (state.multipoint is Section.Unsupported) add("multipoint")
        }
        if (unsupported.isNotEmpty()) add("unsupported=${unsupported.joinToString(",")}")
        state.name.valueOrNull()?.let { add("name=${oneLine(it)}") }
        state.firmware.valueOrNull()?.let { add("firmware=${oneLine(it)}") }
        state.modes.valueOrNull()?.let { modes ->
            add("current=${modes.current}")
            modes.favourites?.let { add("favourites=${it.total}$SEP${it.slots.sorted().joinToString(",")}") }
            for (m in modes.modes) {
                add(
                    "mode=" + listOf(
                        m.index, m.promptB1, m.promptB2, bit(m.editable), bit(m.configured),
                        m.cnc, bit(m.autoCnc), m.spatial.code, bit(m.wind), bit(m.anc), oneLine(m.name),
                    ).joinToString(SEP),
                )
            }
        }
        state.audio.valueOrNull()?.let { a ->
            add("audio=" + listOf(a.cnc, bit(a.autoCnc), a.spatial.code, bit(a.wind), bit(a.anc)).joinToString(SEP))
        }
        state.discreteAnc.valueOrNull()?.let { d ->
            add(
                "discreteAnc=" + listOf(
                    cleanField(d.selectedId),
                    d.ambientLevel?.toString().orEmpty(),
                    d.ambientMax.toString(),
                    d.focusOnVoice?.let { bit(it).toString() }.orEmpty(),
                ).joinToString(SEP),
            )
            for (opt in d.options) {
                add("ancOpt=${cleanField(opt.id)}$SEP${cleanField(opt.label)}$SEP${oneLine(opt.description.orEmpty())}")
            }
        }
        if (state.eq is Section.Loaded) {
            add("eqLoaded=1")
            state.eq.value.forEach { b -> add("eq=" + listOf(b.bandId, b.min, b.max, b.current).joinToString(SEP)) }
        }
        state.selectedEqPreset?.let { add("selectedEqPreset=${cleanField(it)}") }
        state.eqBandLabels?.let { labels -> add("eqLabels=${labels.joinToString(SEP) { cleanField(it) }}") }
        state.eqPresets?.forEach { p ->
            add("eqPreset=${cleanField(p.id)}$SEP${cleanField(p.label)}$SEP${p.values.joinToString(",")}")
        }
        state.selfVoice.valueOrNull()?.let { add("selfVoice=${bit(it.persist)}$SEP${it.level.code}$SEP${it.extra ?: ""}") }
        state.speakToChat.valueOrNull()?.let { add("speakToChat=${bit(it)}") }
        state.autoPlayPause.valueOrNull()?.let { add("autoPlayPause=${bit(it)}") }
        state.autoAnswer.valueOrNull()?.let { add("autoAnswer=${bit(it)}") }
        state.multipoint.valueOrNull()?.let { add("multipoint=${bit(it.enabled)}$SEP${it.raw}") }
    }.joinToString("\n", postfix = "\n")

    fun decode(text: String): HeadphoneState? = runCatching {
        val lines = text.lines().filter { it.isNotBlank() }.map { it.substringBefore("=") to it.substringAfter("=") }
        if (lines.firstOrNull() != ("v" to VERSION)) return null
        fun one(key: String) = lines.firstOrNull { it.first == key }?.second
        fun all(key: String) = lines.filter { it.first == key }.map { it.second }
        fun fields(value: String, limit: Int = 0) = value.split(SEP, limit = limit)

        val unsupported = one("unsupported")?.split(",")?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        fun <T> section(tag: String, value: String?, parse: (String) -> T): Section<T> = when {
            value != null -> Section.Loaded(parse(value))
            tag in unsupported -> Section.Unsupported
            else -> Section.Loading
        }

        val badge = one("badge")?.let { v ->
            val f = fields(v, limit = 3)
            DeviceBadge(brand = f[0], model = f[1], experimental = f[2] == "1")
        }
        val capabilities = one("caps")?.let { v ->
            val f = fields(v, limit = 8)
            Capabilities(
                canRename = f[0] == "1",
                canPair = f[1] == "1",
                canPowerOff = f[2] == "1",
                canEditModes = f[3] == "1",
                supportsModeAncToggle = f[4] == "1",
                editableSlots = decodeRange(f[5]),
                presetSlots = decodeRange(f[6]),
                presetNames = f[7].split(",").filter { it.isNotEmpty() },
            )
        } ?: Capabilities()
        val modes = all("mode").map { line ->
            val f = fields(line, limit = 11)
            ModeConfig(
                index = f[0].toInt(), promptB1 = f[1].toInt(), promptB2 = f[2].toInt(),
                editable = f[3] == "1", configured = f[4] == "1", name = f[10],
                cnc = f[5].toInt(), autoCnc = f[6] == "1", spatial = spatial(f[7]), wind = f[8] == "1", anc = f[9] == "1",
            )
        }
        val favourites = one("favourites")?.let { v ->
            val (total, slots) = fields(v)
            Favourites(total.toInt(), slots.split(",").filter { it.isNotEmpty() }.map { it.toInt() }.toSet())
        }
        val ancOpts = all("ancOpt").map { line ->
            val f = fields(line, limit = 3)
            DiscreteAncOption(id = f[0], label = f[1], description = f[2].ifEmpty { null })
        }
        val discreteAnc = one("discreteAnc")?.let { v ->
            val f = fields(v, limit = 4)
            Section.Loaded(
                DiscreteAncState(
                    options = ancOpts,
                    selectedId = f[0],
                    ambientLevel = f[1].toIntOrNull(),
                    ambientMax = f[2].toInt(),
                    focusOnVoice = f[3].takeIf { it.isNotEmpty() }?.let { it == "1" },
                ),
            )
        } ?: if ("discreteAnc" in unsupported) Section.Unsupported else Section.Loading
        val eqBandLabels = one("eqLabels")?.let { if (it.isEmpty()) emptyList() else fields(it) }
        val eqPresets = all("eqPreset").takeIf { it.isNotEmpty() }?.map { line ->
            val f = fields(line, limit = 3)
            EqPresetChoice(
                id = f[0],
                label = f[1],
                values = f[2].split(",").filter { it.isNotEmpty() }.map { it.toInt() },
            )
        }
        val eqSection = when {
            one("eqLoaded") == "1" || all("eq").isNotEmpty() -> Section.Loaded(
                all("eq").map { v -> fields(v).map(String::toInt).let { EqBand(it[0], it[1], it[2], it[3]) } },
            )
            "eq" in unsupported -> Section.Unsupported
            else -> Section.Loading
        }
        HeadphoneState(
            deviceBadge = badge,
            capabilities = capabilities,
            name = section("name", one("name")) { it },
            firmware = section("firmware", one("firmware")) { it },
            modes = section("modes", one("current")) { ModesSection(modes, it.toInt(), favourites) },
            audio = section("audio", one("audio")) { v ->
                val f = fields(v)
                AudioSettings(cnc = f[0].toInt(), autoCnc = f[1] == "1", spatial = spatial(f[2]), wind = f[3] == "1", anc = f[4] == "1")
            },
            discreteAnc = discreteAnc,
            eq = eqSection,
            selectedEqPreset = one("selectedEqPreset"),
            eqBandLabels = eqBandLabels,
            eqPresets = eqPresets,
            selfVoice = section("selfVoice", one("selfVoice")) { v ->
                val f = fields(v)
                SelfVoice(persist = f[0] == "1", level = SelfVoiceLevel.fromCode(f[1].toInt())!!, extra = f[2].toIntOrNull())
            },
            speakToChat = section("speakToChat", one("speakToChat")) { it == "1" },
            autoPlayPause = section("autoPlayPause", one("autoPlayPause")) { it == "1" },
            autoAnswer = section("autoAnswer", one("autoAnswer")) { it == "1" },
            multipoint = section("multipoint", one("multipoint")) { v -> fields(v).let { Multipoint(it[0] == "1", it[1].toInt()) } },
        )
    }.getOrNull()

    private fun encodeRange(r: IntRange): String = if (r.isEmpty()) "" else "${r.first}..${r.last}"

    private fun decodeRange(s: String): IntRange {
        if (s.isEmpty()) return IntRange.EMPTY
        val (a, b) = s.split("..", limit = 2)
        return a.toInt()..b.toInt()
    }

    private fun spatial(code: String) = Spatial.fromCode(code.toInt())!!

    private fun bit(b: Boolean) = if (b) 1 else 0

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ')

    private fun cleanField(s: String) = oneLine(s).replace(SEP, " ")
}
