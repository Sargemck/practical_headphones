package io.github.sargemck.practicalheadphones.service

import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.Favourites
import io.github.sargemck.practicalheadphones.bmap.ModeConfig
import io.github.sargemck.practicalheadphones.bmap.Spatial
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.ModesSection
import io.github.sargemck.practicalheadphones.state.Section
import io.github.sargemck.practicalheadphones.state.nextFavourite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NotificationModelTest {
    private fun mode(index: Int, name: String) =
        ModeConfig(index, 0, 0, index > 3, true, name, 0, false, Spatial.OFF, wind = false, anc = true)

    private val modes = ModesSection(
        modes = listOf(mode(0, "Quiet"), mode(1, "Aware"), mode(2, "Immersion"), mode(4, "Walk")),
        current = 2,
        favourites = Favourites(11, setOf(0, 1, 4, 9)),
    )

    private val connected = HeadphoneState(
        link = LinkState.Connected,
        name = Section.Loaded("Desk"),
        battery = Section.Loaded(80),
        modes = Section.Loaded(modes),
        audio = Section.Loaded(AudioSettings(0, false, Spatial.OFF, wind = false, anc = true)),
    )

    @Test
    fun `connected shows battery, mode and both actions`() {
        assertEquals(
            NotificationModel("Desk", "Battery 80% · Immersion", listOf(NotificationAction.NextMode, NotificationAction.NoiseCancellingOff)),
            NotificationModel.from(connected),
        )
    }

    @Test
    fun `still loading`() {
        assertEquals(NotificationModel("Headphones", "Loading…", emptyList()), NotificationModel.from(HeadphoneState(link = LinkState.Connected)))
    }

    @Test
    fun `link states show plain text and no actions`() {
        assertEquals(
            NotificationModel("Headphones", "Another app is using your headphones", emptyList()),
            NotificationModel.from(HeadphoneState(link = LinkState.AnotherApp)),
        )
        for (link in LinkState.entries) NotificationModel.linkText(link)
    }

    @Test
    fun `next favourite skips empty slots and wraps`() {
        assertEquals(4, nextFavourite(modes))
        assertEquals(0, nextFavourite(modes.copy(current = 4)))
        assertNull(nextFavourite(modes.copy(favourites = Favourites(11, setOf(9)))))
        assertNull(nextFavourite(modes.copy(favourites = null)))
    }
}
