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
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import androidx.compose.ui.test.performClick
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
            Triple("hero", "hero") { HeroCard(Samples.connected, actions) },
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
        compose.setContent { UiFrame(variant) { Box(Modifier.padding(16.dp)) { HeroCard(state, actions) } } }
        for (link in LinkState.entries - LinkState.Connected) {
            state = Samples.link(link)
            settle()
            compose.onNodeWithTag("hero").captureRoboImage(path("link-${link.name.lowercase()}"))
        }
    }

    /** Disconnected after a load: the last known settings, dimmed. */
    @Test
    fun offline() {
        compose.setContent { UiFrame(variant) { HeadphonesApp(Samples.offline, actions, "0.0.1") } }
        settle()
        compose.onNodeWithTag("main-list").captureRoboImage(path("offline"))
    }

    /** Disconnected with nothing loaded yet in this process. */
    @Test
    fun offlineEmpty() {
        compose.setContent { UiFrame(variant) { HeadphonesApp(Samples.link(LinkState.Disconnected), actions, "0.0.1") } }
        settle()
        compose.onNodeWithTag("main-list").captureRoboImage(path("offline-empty"))
    }

    @Test
    fun allModesSheet() {
        compose.setContent { UiFrame(variant) { HeadphonesApp(Samples.connected, actions, "0.0.1") } }
        compose.onNodeWithTag("all-modes").performClick()
        compose.mainClock.advanceTimeBy(3_000)
        settle()
        // The sheet's slide-in leaves its top edge a sub-pixel off between runs: one anti-aliased row.
        captureScreenRoboImage(path("all-modes-sheet"), RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)))
    }

    @Test
    fun modeEditorNew() {
        compose.setContent { UiFrame(variant) { HeadphonesApp(Samples.connected, actions, "0.0.1", initialScreen = Screen.ModeEditor) } }
        settle()
        compose.onRoot().captureRoboImage(path("mode-editor-new"))
    }

    @Test
    fun modeEditorEdit() {
        compose.setContent {
            UiFrame(variant) { HeadphonesApp(Samples.connected, actions, "0.0.1", initialScreen = Screen.ModeEditor, initialEditorSlot = 5) }
        }
        settle()
        compose.onRoot().captureRoboImage(path("mode-editor-edit"))
    }

    @Test
    fun permissions() {
        var permissions by mutableStateOf(Permissions())
        compose.setContent { UiFrame(variant) { HeadphonesApp(Samples.link(LinkState.Disconnected), actions, "0.0.1", permissions = permissions) } }
        for ((name, value) in listOf(
            "bluetooth" to Permissions(bluetooth = Permission.Denied),
            "bluetooth-blocked" to Permissions(bluetooth = Permission.Blocked),
            "notifications" to Permissions(notifications = Permission.Denied),
        )) {
            permissions = value
            settle()
            compose.onNodeWithTag("main-list").captureRoboImage(path("permission-$name"))
        }
    }

    @Test
    fun autoConnectBanner() {
        compose.setContent { UiFrame(variant) { HeadphonesApp(Samples.connected, actions, "0.0.1", phone = PhoneFeatures(offerAutoConnect = true)) } }
        settle()
        compose.onNodeWithTag("auto-connect-banner").captureRoboImage(path("auto-connect-banner"))
    }

    @Test
    fun renameDialog() = dialog(DeviceDialog.Rename, "rename")

    @Test
    fun pairingDialog() = dialog(DeviceDialog.Pairing, "pairing")

    @Test
    fun powerOffDialog() = dialog(DeviceDialog.PowerOff, "power-off")

    @Test
    fun multiBrandSonyAndEarbuds() {
        val sonyState = io.github.sargemck.practicalheadphones.state.HeadphoneState(
            link = LinkState.Connected,
            name = io.github.sargemck.practicalheadphones.state.Section.Loaded("WH-1000XM5"),
            firmware = io.github.sargemck.practicalheadphones.state.Section.Unsupported,
            battery = io.github.sargemck.practicalheadphones.state.Section.Loaded(82),
            modes = io.github.sargemck.practicalheadphones.state.Section.Unsupported,
            audio = io.github.sargemck.practicalheadphones.state.Section.Unsupported,
            discreteAnc = io.github.sargemck.practicalheadphones.state.Section.Loaded(
                io.github.sargemck.practicalheadphones.state.DiscreteAncState(
                    options = listOf(
                        io.github.sargemck.practicalheadphones.state.DiscreteAncOption("ANC", "Noise cancelling"),
                        io.github.sargemck.practicalheadphones.state.DiscreteAncOption("AMBIENT", "Ambient sound", "Lets outside sound through."),
                        io.github.sargemck.practicalheadphones.state.DiscreteAncOption("OFF", "Off"),
                    ),
                    selectedId = "AMBIENT",
                    ambientLevel = 14,
                    ambientMax = 20,
                    focusOnVoice = true,
                ),
            ),
            eq = io.github.sargemck.practicalheadphones.state.Section.Loaded(
                List(6) { i -> io.github.sargemck.practicalheadphones.bmap.EqBand(i, -10, 10, if (i == 5) 4 else 0) },
            ),
            eqPresets = io.github.sargemck.practicalheadphones.driver.SonyDriver.SONY_PRESETS.take(4),
            eqBandLabels = io.github.sargemck.practicalheadphones.sony.SonyProtocol.EQ_BAND_LABELS,
            selfVoice = io.github.sargemck.practicalheadphones.state.Section.Unsupported,
            speakToChat = io.github.sargemck.practicalheadphones.state.Section.Loaded(true),
            autoPlayPause = io.github.sargemck.practicalheadphones.state.Section.Unsupported,
            autoAnswer = io.github.sargemck.practicalheadphones.state.Section.Unsupported,
            multipoint = io.github.sargemck.practicalheadphones.state.Section.Unsupported,
            deviceBadge = io.github.sargemck.practicalheadphones.state.DeviceBadge("Sony", "WH-1000XM5", experimental = true),
            batteryParts = listOf(
                io.github.sargemck.practicalheadphones.state.BatteryPart("L", 85),
                io.github.sargemck.practicalheadphones.state.BatteryPart("R", 80),
                io.github.sargemck.practicalheadphones.state.BatteryPart("Case", 90),
            ),
            availableDevices = listOf(
                io.github.sargemck.practicalheadphones.state.PairedHeadphoneChoice("01", "WH-1000XM5", "Sony", selected = true),
                io.github.sargemck.practicalheadphones.state.PairedHeadphoneChoice("02", "Bose QC Ultra", "Bose", selected = false),
            ),
            capabilities = io.github.sargemck.practicalheadphones.state.Capabilities(
                canRename = false,
                canPair = false,
                canPowerOff = true,
                canEditModes = false,
            ),
        )
        compose.setContent { UiFrame(variant) { HeadphonesApp(sonyState, actions, "0.0.1") } }
        settle()
        compose.onNodeWithTag("main-list").captureRoboImage(path("multi-brand-sony"))
    }

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
