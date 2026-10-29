package io.github.sargemck.practicalheadphones.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
    )

    private fun show(state: HeadphoneState = Samples.connected, variant: Variant = Variant.LIGHT, screen: Screen = Screen.Main) {
        RuntimeEnvironment.setFontScale(variant.fontScale)
        compose.setContent { UiFrame(variant) { HeadphonesApp(state, actions, "0.0.1", initialScreen = screen) } }
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
        val star = compose.onNodeWithTag("favourite-5")
        assertEquals("Relax, not a favourite", label(star.fetchSemanticsNode()))
        star.performClick()
        assertEquals(listOf("favourite 5"), calls)
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
