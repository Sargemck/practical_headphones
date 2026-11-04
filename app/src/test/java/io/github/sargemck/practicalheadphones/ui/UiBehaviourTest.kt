package io.github.sargemck.practicalheadphones.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sargemck.practicalheadphones.bmap.AudioSettings
import io.github.sargemck.practicalheadphones.state.HeadphoneState
import io.github.sargemck.practicalheadphones.state.LinkState
import io.github.sargemck.practicalheadphones.state.Section
import io.github.sargemck.practicalheadphones.state.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, needed for the overflow checks
@Config(qualifiers = WHOLE_LIST)
class UiBehaviourTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var audioChange: ((AudioSettings) -> AudioSettings)? = null

    private val actions = Actions(
        selectMode = { calls += "mode $it" },
        toggleFavourite = { calls += "favourite $it" },
        setAudio = { audioChange = it },
        setEq = { b, m, t -> calls += "eq $b/$m/$t" },
        setName = { calls += "name '$it'" },
        powerOff = { calls += "power off" },
        pairingMode = { calls += "pairing" },
        reconnect = { calls += "reconnect" },
        createMode = { name, s -> calls += "create '$name' ${s.cnc}/${s.spatial}/${s.wind}/${s.anc}" },
        updateMode = { i, name, s -> calls += "update $i '$name' ${s.cnc}/${s.spatial}/${s.wind}/${s.anc}" },
        deleteMode = { calls += "delete $it" },
        requestBluetooth = { calls += "request bluetooth" },
        openAppSettings = { calls += "app settings" },
        requestNotifications = { calls += "request notifications" },
        addTile = { calls += "add tile" },
        setUpAutoConnect = { calls += "auto-connect" },
    )

    private fun show(
        state: HeadphoneState = Samples.connected,
        variant: Variant = Variant.LIGHT,
        screen: Screen = Screen.Main,
        permissions: Permissions = Permissions(),
        slot: Int? = null,
        phone: PhoneFeatures = PhoneFeatures(),
    ) {
        RuntimeEnvironment.setFontScale(variant.fontScale)
        compose.setContent {
            UiFrame(variant) {
                HeadphonesApp(state, actions, "0.0.1", initialScreen = screen, permissions = permissions, initialEditorSlot = slot, phone = phone)
            }
        }
    }

    // Controls

    @Test
    fun `noise slider shows 10 minus cnc and writes it back inverted`() {
        val audio = Samples.connected.audio.valueOrNull()!!.copy(cnc = 3)
        show(Samples.connected.copy(audio = Section.Loaded(audio)))
        val slider = compose.onNodeWithTag("noise-slider")
        assertEquals("Block noise, 7 of 10", slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals(7f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        assertEquals(8, audioChange!!(audio).cnc)
    }

    @Test
    fun `tapping a preset sends its values`() {
        show()
        compose.onNodeWithTag("eq-voice").performClick()
        compose.onNodeWithTag("eq-bass").performClick()
        assertEquals(listOf("eq -2/3/2", "eq 6/0/0"), calls)
    }

    @Test
    fun `the star toggles that mode's favourite`() {
        show()
        compose.onNodeWithTag("all-modes").performClick()
        val star = compose.onNodeWithTag("favourite-5")
        assertEquals("Relax, not a favourite", label(star.fetchSemanticsNode()))
        star.performClick()
        assertEquals(listOf("favourite 5"), calls)
    }

    @Test
    fun `the card shows four modes and the sheet shows all of them`() {
        show()
        for (i in listOf(0, 1, 2, 4)) compose.onNodeWithTag("mode-$i").assertExists()
        compose.onNodeWithTag("mode-5").assertDoesNotExist()
        compose.onNodeWithTag("all-modes").performClick()
        compose.onNodeWithTag("sheet-mode-5").performClick()
        assertEquals(listOf("mode 5"), calls)
    }

    @Test
    fun `tapping a mode switches to it`() {
        show()
        compose.onNodeWithTag("mode-4").performClick()
        assertEquals(listOf("mode 4"), calls)
    }

    @Test
    fun `rename rejects blank and too long names, and trims`() {
        show()
        compose.onNodeWithTag("rename").performClick()
        val field = compose.onNodeWithTag("rename-field")
        field.performTextReplacement("   ")
        compose.onNodeWithTag("rename-confirm").assertIsNotEnabled()
        field.performTextReplacement("x".repeat(32))
        compose.onNodeWithTag("rename-confirm").assertIsNotEnabled()
        field.performTextReplacement("é".repeat(16)) // 32 UTF-8 bytes
        compose.onNodeWithTag("rename-confirm").assertIsNotEnabled()
        field.performTextReplacement("  Desk pair  ")
        compose.onNodeWithTag("rename-confirm").assertIsEnabled().performClick()
        assertEquals(listOf("name 'Desk pair'"), calls)
    }

    @Test
    fun `power off and pairing mode fire only after confirming`() {
        show()
        compose.onNodeWithTag("power-off").performClick()
        compose.onNodeWithTag("dialog-cancel").performClick()
        compose.onNodeWithTag("pairing-mode").performClick()
        compose.onNodeWithTag("dialog-cancel").performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("power-off").performClick()
        compose.onNodeWithTag("power-off-confirm").performClick()
        compose.onNodeWithTag("pairing-mode").performClick()
        compose.onNodeWithTag("pairing-confirm").performClick()
        assertEquals(listOf("power off", "pairing"), calls)
    }

    @Test
    fun `reconnect is offered when another app has the link`() {
        show(Samples.link(LinkState.AnotherApp))
        compose.onNodeWithTag("reconnect").performClick()
        assertEquals(listOf("reconnect"), calls)
    }

    @Test
    fun `while disconnected the last known settings show but can't be changed`() {
        show(Samples.offline)
        compose.onNodeWithTag("mode-4").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("wind-block").assertIsNotEnabled()
        compose.onNodeWithTag("self-voice-low").assertIsNotEnabled()
        compose.onNodeWithTag("all-modes").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("all-modes-sheet").assertDoesNotExist()
        compose.onNodeWithTag("eq-voice").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("noise-slider").assertIsNotEnabled()
        compose.onNodeWithTag("rename").assertDoesNotExist()
        compose.onNodeWithTag("power-off").assertDoesNotExist()
        compose.onNodeWithTag("open-bluetooth").assertIsEnabled()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `no text overflows at font scale 2 while disconnected`() {
        show(Samples.offline, Variant.FONT2)
        assertNoOverflow()
    }

    // Mode editor

    private fun openSheet() {
        compose.onNodeWithTag("all-modes").performClick()
        compose.mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun `new mode starts from the current noise settings and needs a valid name`() {
        show()
        openSheet()
        compose.onNodeWithTag("new-mode").performClick()
        compose.onNodeWithTag("mode-editor").assertExists()
        val save = compose.onNodeWithTag("mode-save")
        save.assertIsNotEnabled()
        val field = compose.onNodeWithTag("mode-name")
        for ((name, error) in listOf("Relax" to "You already have a mode with this name.", "quiet" to "A Bose preset already has this name.")) {
            field.performTextReplacement(name)
            save.assertIsNotEnabled()
            compose.onNodeWithText(error).assertExists()
        }
        field.performTextReplacement(" Hike ")
        compose.onNodeWithTag("editor-wind-block").performClick()
        save.assertIsEnabled().performClick()
        val audio = Samples.connected.audio.valueOrNull()!!
        assertEquals(listOf("create ' Hike ' ${audio.cnc}/${audio.spatial}/${!audio.wind}/${audio.anc}"), calls)
        compose.onNodeWithTag("main-list").assertExists()
    }

    @Test
    fun `presets have no edit button, custom modes do`() {
        show()
        openSheet()
        compose.onNodeWithTag("edit-mode-0").assertDoesNotExist()
        assertEquals("Edit Relax", label(compose.onNodeWithTag("edit-mode-5").fetchSemanticsNode()))
    }

    @Test
    fun `editing saves only after a change, and keeps the slot`() {
        show(screen = Screen.ModeEditor, slot = 5)
        val save = compose.onNodeWithTag("mode-save")
        save.assertIsNotEnabled()
        compose.onNodeWithTag("mode-name").performTextReplacement("Calm")
        save.assertIsEnabled().performClick()
        val relax = Samples.connected.modes.valueOrNull()!!.modes.first { it.index == 5 }
        assertEquals(listOf("update 5 'Calm' ${relax.cnc}/${relax.spatial}/${relax.wind}/${relax.anc}"), calls)
    }

    @Test
    fun `delete asks first`() {
        show(screen = Screen.ModeEditor, slot = 5)
        compose.onNodeWithTag("mode-delete").performClick()
        compose.onNodeWithTag("dialog-cancel").performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("mode-delete").performClick()
        compose.onNodeWithTag("delete-mode-confirm").performClick()
        assertEquals(listOf("delete 5"), calls)
    }

    @Test
    fun `with no free slot the sheet says so instead of offering a new mode`() {
        val section = Samples.connected.modes.valueOrNull()!!
        val template = section.modes.first { it.index == 5 }
        val full = section.copy(modes = section.modes + (8..10).map { i -> template.copy(index = i, name = "Mode $i") })
        show(Samples.connected.copy(modes = Section.Loaded(full)))
        openSheet()
        compose.onNodeWithTag("new-mode").assertDoesNotExist()
        compose.onNodeWithTag("modes-full").assertExists()
    }

    @Test
    fun `the editor can't save while disconnected`() {
        show(Samples.connected.copy(link = LinkState.Lost), screen = Screen.ModeEditor, slot = 5)
        compose.onNodeWithTag("mode-name").performTextReplacement("Calm")
        compose.onNodeWithTag("mode-save").assertIsNotEnabled()
        compose.onNodeWithTag("editor-offline").assertExists()
    }

    @Test
    fun `the sheet offers the Quick Settings tile only when it can be added`() {
        show()
        openSheet()
        compose.onNodeWithTag("add-tile").assertDoesNotExist()
    }

    @Test
    fun `adding the tile asks the system`() {
        show(phone = PhoneFeatures(canAddTile = true))
        openSheet()
        compose.onNodeWithTag("add-tile").performClick()
        assertEquals(listOf("add tile"), calls)
    }

    @Test
    fun `auto-connect is offered once, under the hero, and can be set up or dismissed`() {
        show(phone = PhoneFeatures(offerAutoConnect = true))
        compose.onNodeWithTag("auto-connect-set-up").performClick()
        compose.onNodeWithTag("auto-connect-dismiss").performClick()
        compose.onNodeWithTag("auto-connect-banner").assertDoesNotExist()
        assertEquals(listOf("auto-connect"), calls)
    }

    @Test
    fun `the notifications banner goes first, auto-connect waits`() {
        show(permissions = Permissions(notifications = Permission.Denied), phone = PhoneFeatures(offerAutoConnect = true))
        compose.onNodeWithTag("auto-connect-banner").assertDoesNotExist()
        compose.onNodeWithTag("notifications-dismiss").performClick()
        compose.onNodeWithTag("auto-connect-banner").assertExists()
    }

    // Permissions

    @Test
    fun `missing Bluetooth permission asks, then points to settings once blocked`() {
        var permissions by mutableStateOf(Permissions(bluetooth = Permission.Denied))
        compose.setContent { UiFrame(Variant.LIGHT) { HeadphonesApp(Samples.link(LinkState.Disconnected), actions, "0.0.1", permissions = permissions) } }
        compose.onNodeWithTag("allow-bluetooth").performClick()
        permissions = Permissions(bluetooth = Permission.Blocked)
        compose.onNodeWithTag("open-app-settings").performClick()
        assertEquals(listOf("request bluetooth", "app settings"), calls)
        compose.onNodeWithTag("notifications-banner").assertDoesNotExist()
    }

    @Test
    fun `the notifications banner can be turned on or dismissed`() {
        show(permissions = Permissions(notifications = Permission.Denied))
        compose.onNodeWithTag("notifications-allow").performClick()
        compose.onNodeWithTag("notifications-dismiss").performClick()
        compose.onNodeWithTag("notifications-banner").assertDoesNotExist()
        assertEquals(listOf("request notifications"), calls)
    }

    @Test
    fun `no text overflows at font scale 2 in the mode editor`() {
        show(variant = Variant.FONT2, screen = Screen.ModeEditor, slot = 5)
        assertNoOverflow()
    }

    // Text and accessibility, at the largest font scale

    @Test
    fun `no text overflows at font scale 2 on the main screen`() {
        show(variant = Variant.FONT2)
        assertNoOverflow()
    }

    @Test
    fun `no text overflows at font scale 2 with custom EQ`() {
        show(Samples.customEq, Variant.FONT2)
        compose.onNodeWithTag("eq-custom").performClick()
        assertNoOverflow()
    }

    @Test
    fun `no text overflows at font scale 2 on About`() {
        show(variant = Variant.FONT2, screen = Screen.About)
        assertNoOverflow()
    }

    @Test
    fun `no text overflows at font scale 2 in the dialogs`() {
        show(variant = Variant.FONT2)
        for (tag in listOf("rename", "pairing-mode", "power-off")) {
            compose.onNodeWithTag("main-list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick()
            assertNoOverflow(isDialog())
            compose.onNodeWithTag("dialog-cancel").performClick()
        }
    }

    @Test
    fun `the overflow check catches cut-off text`() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            UiFrame(Variant.FONT2) {
                Column(Modifier.width(120.dp)) {
                    Text("Power off the headphones now", maxLines = 1)
                    Text("Unbreakablewordthatistoolong", softWrap = false)
                    Text("Clipped by height", modifier = Modifier.height(8.dp))
                }
            }
        }
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { node -> mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.single() }
        assertEquals(listOf(true, true, true), texts.map(::clipped))
    }

    @Test
    fun `every clickable node and slider has a label`() {
        show(Samples.connected.copy(boseAppInstalled = true))
        compose.onNodeWithTag("all-modes").performClick() // the sheet's rows and stars too
        val nodes = compose.onAllNodes(hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .fetchSemanticsNodes()
        assertTrue(nodes.size > 30)
        val unlabelled = nodes.filter { label(it).isBlank() && !hasSetTextAction().matches(it) }
        assertEquals(emptyList<SemanticsNode>(), unlabelled)
    }

    @Test
    fun `icon-only buttons are labelled`() {
        show()
        assertEquals("About", label(compose.onNodeWithTag("about").fetchSemanticsNode()))
        compose.onNodeWithTag("all-modes").performClick()
        assertEquals("Quiet, favourite", label(compose.onNodeWithTag("favourite-0").fetchSemanticsNode()))
        compose.onNodeWithTag("about").performClick()
        assertEquals("Back", label(compose.onNodeWithTag("back").fetchSemanticsNode()))
    }

    private fun label(node: SemanticsNode): String =
        listOfNotNull(
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString(),
        ).joinToString().trim()

    /**
     * Cut-off text: taller than its box, past its max lines, ellipsized, or a line wider than the
     * node. (`didOverflowWidth` isn't used: Compose sets it for any text narrower than its constraint.)
     */
    private fun clipped(r: TextLayoutResult): Boolean =
        r.didOverflowHeight || r.multiParagraph.didExceedMaxLines ||
            (0 until r.lineCount).any { r.isLineEllipsized(it) || r.getLineRight(it) > r.size.width + 0.5f }

    /** Every text node in the tree (or under [root]) fits its layout without clipping. */
    private fun assertNoOverflow(root: SemanticsMatcher? = null) {
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        val nodes = compose.onAllNodes(if (root == null) matcher else matcher and SemanticsMatcher("in dialog") { n ->
            generateSequence(n) { it.parent }.any { root.matches(it) }
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no text found", nodes.isNotEmpty())
        val overflowing = nodes.mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.firstOrNull(::clipped)?.layoutInput?.text?.text
        }
        assertEquals(emptyList<String>(), overflowing)
    }
}

/** Same width as a phone, tall enough that the whole list is composed at font scale 2.0. */
const val WHOLE_LIST = "w411dp-h6000dp-mdpi"
