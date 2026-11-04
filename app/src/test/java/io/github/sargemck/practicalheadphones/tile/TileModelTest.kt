package io.github.sargemck.practicalheadphones.tile

import io.github.sargemck.practicalheadphones.bmap.Favourites
import io.github.sargemck.practicalheadphones.bmap.ModeConfig
import io.github.sargemck.practicalheadphones.bmap.Spatial
import io.github.sargemck.practicalheadphones.service.NotificationModel
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.ModesSection
import io.github.sargemck.practicalheadphones.state.Section
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TileModelTest {
    private fun mode(index: Int, name: String) =
        ModeConfig(index, 0, 0, index > 3, true, name, 0, false, Spatial.OFF, wind = false, anc = true)

    private val modes = ModesSection(
        modes = listOf(mode(0, "Quiet"), mode(1, "Aware"), mode(2, "Immersion"), mode(4, "Walk ")),
        current = 2,
        favourites = Favourites(11, setOf(0, 1, 4, 9)),
    )

    private val connected = HeadphoneState(link = LinkState.Connected, modes = Section.Loaded(modes))

    @Test
    fun `current mode as the label, the next starred mode as the subtitle`() {
        assertEquals(TileModel(TileModel.Status.Active, "Immersion", "Next: Walk", 4), TileModel.from(connected))
    }

    @Test
    fun `the next starred mode wraps around`() {
        val atWalk = connected.copy(modes = Section.Loaded(modes.copy(current = 4)))
        assertEquals(TileModel(TileModel.Status.Active, "Walk", "Next: Quiet", 0), TileModel.from(atWalk))
    }

    @Test
    fun `nothing to cycle to`() {
        val onlyCurrent = connected.copy(modes = Section.Loaded(modes.copy(favourites = Favourites(11, setOf(2)))))
        assertEquals(TileModel(TileModel.Status.Active, "Immersion", "Star modes to cycle", null), TileModel.from(onlyCurrent))
        val none = connected.copy(modes = Section.Loaded(modes.copy(favourites = null)))
        assertEquals(null, TileModel.from(none).next)
    }

    @Test
    fun `loading while connected`() {
        assertEquals(TileModel(TileModel.Status.Inactive, "Headphones", "Loading…", null), TileModel.from(HeadphoneState(link = LinkState.Connected)))
    }

    @Test
    fun `unavailable when not connected or the service isn't running`() {
        assertEquals(TileModel(TileModel.Status.Unavailable, "Headphones", "Not connected", null), TileModel.from(null))
        for (link in LinkState.entries - LinkState.Connected) {
            val model = TileModel.from(connected.copy(link = link))
            assertEquals(TileModel(TileModel.Status.Unavailable, "Headphones", NotificationModel.linkText(link), null), model, link.name)
        }
    }
}
