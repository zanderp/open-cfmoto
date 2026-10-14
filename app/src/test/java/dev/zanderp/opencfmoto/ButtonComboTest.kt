package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonComboTest {

    private val back = ButtonGesture.NAV_BACK
    private val fwd = ButtonGesture.NAV_FWD

    @Test
    fun backThenFwd_inWindow_firesBackThenFwdCombo() {
        val c = ButtonCombo(windowMs = 400)
        assertNull(c.onSingle(back, 1_000))
        assertEquals(ButtonGesture.NAV_BACK_THEN_FWD, c.onSingle(fwd, 1_300))
    }

    @Test
    fun fwdThenBack_inWindow_firesFwdThenBackCombo() {
        val c = ButtonCombo(windowMs = 400)
        assertNull(c.onSingle(fwd, 1_000))
        assertEquals(ButtonGesture.NAV_FWD_THEN_BACK, c.onSingle(back, 1_400))
    }

    @Test
    fun opposite_outsideWindow_isJustTwoSteps() {
        val c = ButtonCombo(windowMs = 400)
        assertNull(c.onSingle(back, 1_000))
        assertNull(c.onSingle(fwd, 1_401))
    }

    @Test
    fun sameDirection_neverCombos() {
        val c = ButtonCombo(windowMs = 400)
        assertNull(c.onSingle(fwd, 1_000))
        assertNull(c.onSingle(fwd, 1_100))
        assertNull(c.onSingle(fwd, 1_200))
    }

    @Test
    fun comboConsumesBothTaps_soTripleOnlyFiresOnce() {
        val c = ButtonCombo(windowMs = 400)
        assertNull(c.onSingle(back, 1_000))
        assertEquals(ButtonGesture.NAV_BACK_THEN_FWD, c.onSingle(fwd, 1_100))
        assertNull(c.onSingle(back, 1_200))
    }

    @Test
    fun selectBetween_breaksTheCombo() {
        val c = ButtonCombo(windowMs = 400)
        assertNull(c.onSingle(back, 1_000))
        assertNull(c.onSingle(ButtonGesture.SELECT_PRESS, 1_100))
        assertNull(c.onSingle(fwd, 1_200))
    }

    @Test
    fun reset_forgetsPreviousTap() {
        val c = ButtonCombo(windowMs = 400)
        assertNull(c.onSingle(back, 1_000))
        c.reset()
        assertNull(c.onSingle(fwd, 1_100))
    }

    @Test
    fun backSetPreset_mapsCombosToHomeAndBack() {
        val map = ButtonClusterPreset.BACK_SET.mapping()
        assertEquals(ButtonAction.HOME, map[ButtonGesture.NAV_BACK_THEN_FWD])
        assertEquals(ButtonAction.BACK, map[ButtonGesture.NAV_FWD_THEN_BACK])
    }

    @Test
    fun otherPresets_leaveCombosOff() {
        for (p in listOf(ButtonClusterPreset.FIVE_WAY, ButtonClusterPreset.MODE_ENT)) {
            assertEquals(ButtonAction.NONE, p.mapping()[ButtonGesture.NAV_BACK_THEN_FWD])
            assertEquals(ButtonAction.NONE, p.mapping()[ButtonGesture.NAV_FWD_THEN_BACK])
        }
    }
}
