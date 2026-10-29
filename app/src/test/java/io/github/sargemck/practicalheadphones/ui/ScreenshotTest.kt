package io.github.sargemck.practicalheadphones.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots verified against the goldens in `src/test/screenshots` on every test run.
 * Re-record with `./gradlew recordRoborazziDebug`. Runs once per [Variant].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ScreenshotTest(private val variant: Variant) {
    @get:Rule val compose = createComposeRule()

    private fun path(name: String) = "src/test/screenshots/$name-${variant.name.lowercase()}.png"

    private val actions = Actions()

    /** Waits for a drawn frame before a capture. */
    private fun settle() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** The real setting too, since dialogs get their own window and density. */
    @Before
    fun fontScale() {
        RuntimeEnvironment.setFontScale(variant.fontScale)
    }

    @Test
    fun mainScreen() {
        compose.setContent { UiFrame(variant) { HeadphonesApp(Samples.connected, actions, "0.0.1") } }
        settle()
        compose.onNodeWithTag("main-list").captureRoboImage(path("main"))
    }

    @Test
    @Config(qualifiers = TALL)
    fun sections() {
        var card by mutableStateOf<@Composable () -> Unit>({})
        compose.setContent { UiFrame(variant) { Box(Modifier.padding(16.dp)) { card() } } }
        val cards: List<Triple<String, String, @Composable () -> Unit>> = listOf(
            Triple("header", "card-header") { HeaderCard(Samples.connected, actions) },
            Triple("modes", "card-modes") { ModesCard(Samples.connected, actions) },
            Triple("noise", "card-noise") { NoiseCard(Samples.connected, actions) },
            Triple("sound", "card-sound") { SoundCard(Samples.connected, actions) },
            Triple("sound-custom", "card-sound") { SoundCard(Samples.customEq, actions) },
            Triple("calls", "card-calls") { CallsCard(Samples.connected, actions) },
            Triple("device", "card-device") { DeviceCard(Samples.connected, actions, {}, {}, {}) },
            Triple("bose-banner", "bose-banner") { BoseBanner(actions) },
            Triple("failed", "card-calls") { CallsCard(Samples.failed, actions) },
        )
        for ((name, tag, content) in cards) {
            card = content
            settle()
            compose.onNodeWithTag(tag).captureRoboImage(path("section-$name"))
        }
    }

    @Test
    fun loading() {
        compose.setContent { UiFrame(variant) { HeadphonesApp(Samples.loading, actions, "0.0.1") } }
        settle()
        compose.onNodeWithTag("main-list").captureRoboImage(path("loading"))
    }

    @Test
    fun linkStates() {
        var state by mutableStateOf(HeadphoneState())
        compose.setContent { UiFrame(variant) { Box(Modifier.padding(16.dp)) { StatusCard(state.link, actions) } } }
        for (link in LinkState.entries - LinkState.Connected) {
            state = Samples.link(link)
            settle()
            compose.onNodeWithTag("status-card").captureRoboImage(path("link-${link.name.lowercase()}"))
        }
    }

    @Test
    fun renameDialog() = dialog(DeviceDialog.Rename, "rename")

    @Test
    fun pairingDialog() = dialog(DeviceDialog.Pairing, "pairing")

    @Test
    fun powerOffDialog() = dialog(DeviceDialog.PowerOff, "power-off")

    private fun dialog(dialog: DeviceDialog, name: String) {
        compose.setContent { UiFrame(variant) { HeadphonesApp(Samples.connected, actions, "0.0.1", initialDialog = dialog) } }
        settle()
        captureScreenRoboImage(path("dialog-$name"))
    }

    @Test
    fun about() {
        compose.setContent { UiFrame(variant) { HeadphonesApp(Samples.connected, actions, "0.0.1", initialScreen = Screen.About) } }
        settle()
        compose.onNodeWithTag("about-list").captureRoboImage(path("about"))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = Variant.entries.map { arrayOf(it) }
    }
}

/** A typical phone. */
const val PHONE = "w411dp-h914dp-xhdpi"

/** Tall enough for the longest card at font scale 2.0. */
const val TALL = "w411dp-h2400dp-xhdpi"
