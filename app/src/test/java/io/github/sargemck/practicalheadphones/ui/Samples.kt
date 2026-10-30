package io.github.sargemck.practicalheadphones.ui

import io.github.sargemck.practicalheadphones.bmap.BmapOp
import io.github.sargemck.practicalheadphones.bmap.Fixtures
import io.github.sargemck.practicalheadphones.bmap.Parsers
import io.github.sargemck.practicalheadphones.bmap.QcUltra2
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.ModesSection
import io.github.sargemck.practicalheadphones.state.Section

/** States for UI tests, built from the M0 fixtures so they show real values. */
object Samples {
    private fun <T> loaded(name: String, parse: (ByteArray) -> T): Section<T> =
        Section.Loaded(parse(Fixtures.load(name).replyPayload))

    val connected: HeadphoneState by lazy {
        val modes = Fixtures.load("31.1-START").replyFrames
            .filter { it.address == QcUltra2.modeConfig && it.op == BmapOp.STATUS }
            .map { Parsers.modeConfig(it.payload) }
            .filter { it.name.isNotBlank() && !it.name.trim().equals("None", ignoreCase = true) }
        HeadphoneState(
            link = LinkState.Connected,
            name = loaded("1.2-GET", Parsers::productName),
            firmware = loaded("0.5-GET", Parsers::firmware),
            battery = loaded("2.2-GET", Parsers::battery),
            modes = Section.Loaded(
                ModesSection(
                    modes = modes,
                    current = Parsers.currentMode(Fixtures.load("31.3-GET").replyPayload),
                    favourites = Parsers.favourites(Fixtures.load("31.8-GET").replyPayload),
                ),
            ),
            audio = loaded("31.10-GET", Parsers::audioSettings),
            eq = loaded("1.7-GET", Parsers::eq),
            selfVoice = loaded("1.11-GET", Parsers::selfVoice),
            autoPlayPause = loaded("1.24-GET", Parsers::bool),
            autoAnswer = loaded("1.27-GET", Parsers::bool),
            multipoint = loaded("1.10-GET", Parsers::multipoint),
        )
    }

    val loading = HeadphoneState(link = LinkState.Connected)

    val failed: HeadphoneState by lazy {
        connected.copy(eq = Section.Failed("Runtime"), selfVoice = Section.Failed("Runtime"), battery = Section.Loading)
    }

    /** A custom EQ, so the sliders show. */
    val customEq: HeadphoneState by lazy {
        val bands = (connected.eq as Section.Loaded).value
        connected.copy(eq = Section.Loaded(bands.mapIndexed { i, b -> b.copy(current = listOf(3, -4, 1)[i]) }))
    }

    fun link(link: LinkState) = HeadphoneState(link = link)
}
