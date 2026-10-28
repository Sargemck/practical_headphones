package io.github.sargemck.practicalheadphones.bmap

/**
 * The M0 device captures in `resources/fixtures/qc-ultra2/fw-10.12.12/`, one request and its
 * reply per file (redacted; see the fixtures README). Loaded by name from the classpath.
 */
object Fixtures {
    const val DIR = "fixtures/qc-ultra2/fw-10.12.12"

    val NAMES = listOf(
        "0.5-GET", "1.2-GET", "1.4-GET", "1.7-GET", "1.9-GET", "1.10-GET", "1.11-GET", "1.24-GET",
        "1.27-GET", "2.2-GET", "4.4-GET", "4.5-GET", "5.1-GET", "31.1-START", "31.3-GET", "31.4-GET",
        "31.8-GET", "31.10-GET",
    )

    /** [reply] is null when the device didn't answer. */
    class Exchange(val name: String, val request: BmapFrame, val reply: ByteArray?) {
        val replyFrames: List<BmapFrame> by lazy { reply?.let(::frames).orEmpty() }

        /** Payload of the single reply frame. */
        val replyPayload: ByteArray get() = replyFrames.single().payload
    }

    fun load(name: String): Exchange {
        val path = "$DIR/$name.txt"
        val text = Fixtures::class.java.classLoader.getResourceAsStream(path)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("fixture not found: $path")
        val lines = text.lines()
        val request = lines.single { it.startsWith(">") }.let { frames(Hex.parse(it.drop(1))).single() }
        val reply = lines.singleOrNull { it.startsWith("<") }?.let { Hex.parse(it.drop(1)) }
        return Exchange(name, request, reply)
    }

    fun all(): List<Exchange> = NAMES.map(::load)

    private fun frames(bytes: ByteArray): List<BmapFrame> =
        FrameParser().feed(bytes).map { (it as FrameEvent.Frame).frame }
}
