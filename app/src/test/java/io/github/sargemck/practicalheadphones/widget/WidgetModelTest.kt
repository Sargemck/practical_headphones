package io.github.sargemck.practicalheadphones.widget

import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.bmap.Favourites
import io.github.sargemck.practicalheadphones.bmap.ModeConfig
import io.github.sargemck.practicalheadphones.bmap.Spatial
import io.github.sargemck.practicalheadphones.state.BatteryPart
import io.github.sargemck.practicalheadphones.state.DiscreteAncOption
import io.github.sargemck.practicalheadphones.state.DiscreteAncState
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.ModesSection
import io.github.sargemck.practicalheadphones.state.Section
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WidgetModelTest {
    @Test
    fun `stopped service shows default prompt`() {
        val m = WidgetModel.from(null)
        assertEquals("Headphones", m.title)
        assertEquals("Tap to open", m.subtitle)
        assertNull(m.primaryAction)
        assertNull(m.secondaryAction)
    }

    @Test
    fun `connected Bose headphones show battery mode and both actions`() {
        val modes = ModesSection(
            modes = listOf(
                ModeConfig(0, 0, 0, false, true, "Quiet", 0, false, Spatial.OFF, false, true),
                ModeConfig(1, 0, 0, false, true, "Aware", 10, false, Spatial.OFF, false, true),
            ),
            current = 0,
            favourites = Favourites(11, setOf(0, 1)),
        )
        val state = HeadphoneState(
            link = LinkState.Connected,
            name = Section.Loaded("QC Ultra"),
            battery = Section.Loaded(85),
            modes = Section.Loaded(modes),
            audio = Section.Loaded(AudioSettings(0, false, Spatial.OFF, false, true)),
        )
        assertEquals(
            WidgetModel("QC Ultra", "85% · Quiet", WidgetAction.NextMode, WidgetAction.AncOff),
            WidgetModel.from(state),
        )
    }

    @Test
    fun `connected earbuds show L R Case battery and cycle discrete ANC`() {
        val state = HeadphoneState(
            link = LinkState.Connected,
            name = Section.Loaded("WF-1000XM5"),
            battery = Section.Loaded(80),
            batteryParts = listOf(BatteryPart("L", 85), BatteryPart("R", 80), BatteryPart("Case", 90)),
            modes = Section.Unsupported,
            audio = Section.Unsupported,
            discreteAnc = Section.Loaded(
                DiscreteAncState(
                    options = listOf(
                        DiscreteAncOption("ANC", "Noise cancelling"),
                        DiscreteAncOption("AMBIENT", "Ambient sound"),
                        DiscreteAncOption("OFF", "Off"),
                    ),
                    selectedId = "OFF",
                ),
            ),
        )
        assertEquals(
            WidgetModel("WF-1000XM5", "L:85% R:80% Case:90% · Off", WidgetAction.NextMode, WidgetAction.AncOn),
            WidgetModel.from(state),
        )
    }

    @Test
    fun `another app contention exposes Reconnect action`() {
        val state = HeadphoneState(
            link = LinkState.AnotherApp,
            lastKnown = HeadphoneState(name = Section.Loaded("QC Ultra")),
        )
        assertEquals(
            WidgetModel("QC Ultra", "Another app is using your headphones", WidgetAction.Reconnect, null),
            WidgetModel.from(state),
        )
    }
}
