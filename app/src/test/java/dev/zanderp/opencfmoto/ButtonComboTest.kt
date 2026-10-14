package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonComboTest {

    private val back = ButtonGesture.NAV_BACK
    private val fwd = ButtonGesture.NAV_FWD

    @Test
    fun backThenFwd_inWindow_firesBackThenFwdCombo() {
        val c = ButtonCombo()
        assertNull(c.onSingle(back, 1_000, 400))
        assertEquals(ButtonGesture.NAV_BACK_THEN_FWD, c.onSingle(fwd, 1_300, 400))
    }

    @Test
    fun fwdThenBack_inWindow_firesFwdThenBackCombo() {
        val c = ButtonCombo()
        assertNull(c.onSingle(fwd, 1_000, 400))
        assertEquals(ButtonGesture.NAV_FWD_THEN_BACK, c.onSingle(back, 1_400, 400))
    }

    @Test
    fun opposite_outsideWindow_isJustTwoSteps() {
        val c = ButtonCombo()
        assertNull(c.onSingle(back, 1_000, 400))
        assertNull(c.onSingle(fwd, 1_401, 400))
    }

    @Test
    fun widerWindow_catchesSlowDashes() {
        val c = ButtonCombo()
        assertNull(c.onSingle(back, 1_000, 1_000))
        assertEquals(ButtonGesture.NAV_BACK_THEN_FWD, c.onSingle(fwd, 1_900, 1_000))
    }

    @Test
    fun windowFor_neverBelowFloor() {
        assertEquals(ButtonCombo.MIN_WINDOW_MS, ButtonCombo.windowFor(200))
        assertEquals(ButtonCombo.MIN_WINDOW_MS, ButtonCombo.windowFor(300))
        assertEquals(450L, ButtonCombo.windowFor(450))
        assertEquals(1_000L, ButtonCombo.windowFor(1_000))
    }

    @Test
    fun sameDirection_neverCombos() {
        val c = ButtonCombo()
        assertNull(c.onSingle(fwd, 1_000))
        assertNull(c.onSingle(fwd, 1_100))
        assertNull(c.onSingle(fwd, 1_200))
    }

    @Test
    fun comboConsumesBothTaps_soTripleOnlyFiresOnce() {
        val c = ButtonCombo()
        assertNull(c.onSingle(back, 1_000))
        assertEquals(ButtonGesture.NAV_BACK_THEN_FWD, c.onSingle(fwd, 1_100))
        assertNull(c.onSingle(back, 1_200))
    }

    @Test
    fun selectBetween_breaksTheCombo() {
        val c = ButtonCombo()
        assertNull(c.onSingle(back, 1_000))
        assertNull(c.onSingle(ButtonGesture.SELECT_PRESS, 1_100))
        assertNull(c.onSingle(fwd, 1_200))
    }

    @Test
    fun reset_forgetsPreviousTap() {
        val c = ButtonCombo()
        assertNull(c.onSingle(back, 1_000))
        c.reset()
        assertNull(c.onSingle(fwd, 1_100))
    }

    @Test
    fun comboPreset_firstPressPicksAction_matchingDoubles() {
        val map = ButtonClusterPreset.BACK_SET_COMBO.mapping()
        assertEquals(ButtonAction.BACK, map[ButtonGesture.NAV_BACK_THEN_FWD])
        assertEquals(ButtonAction.HOME, map[ButtonGesture.NAV_FWD_THEN_BACK])
        // ▲-first = Back and ▼-first = Home on the ×2 rows too.
        assertEquals(ButtonAction.BACK, map[ButtonGesture.NAV_BACK_DOUBLE])
        assertEquals(ButtonAction.HOME, map[ButtonGesture.NAV_FWD_DOUBLE])
    }

    @Test
    fun comboPreset_keepsBackSetSinglesAndOnlyTrustsCoalescedDoubles() {
        val combo = ButtonClusterPreset.BACK_SET_COMBO
        val m = combo.mapping()
        assertEquals(ButtonAction.KNOB_BACK, m[ButtonGesture.NAV_BACK])
        assertEquals(ButtonAction.KNOB_FORWARD, m[ButtonGesture.NAV_FWD])
        assertEquals(ButtonAction.SELECT, m[ButtonGesture.SELECT_PRESS])
        assertTrue(combo.instantSingles)
        assertFalse(combo.navTapDoubles)
    }

    @Test
    fun otherPresets_leaveCombosOffAndTapDoublesOn() {
        for (p in listOf(ButtonClusterPreset.FIVE_WAY, ButtonClusterPreset.MODE_ENT, ButtonClusterPreset.BACK_SET)) {
            val m = p.mapping()
            for (g in listOf(ButtonGesture.NAV_BACK_THEN_FWD, ButtonGesture.NAV_FWD_THEN_BACK)) {
                assertEquals(ButtonAction.NONE, m[g] ?: g.default)
            }
            assertTrue(p.navTapDoubles)
        }
    }
}
