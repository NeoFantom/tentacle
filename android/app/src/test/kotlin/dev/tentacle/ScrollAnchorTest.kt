package dev.tentacle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScrollAnchorTest {
    @Test fun `banner appearing above the touched button preserves its screen position`() {
        assertEquals(680, anchoredScrollY(420, 600, 500, 1000))
    }

    @Test fun `removing content above the anchor scrolls back by the removed height`() {
        assertEquals(320, anchoredScrollY(420, 240, 500, 1000))
    }

    @Test fun `expansion below the touched header does not scroll`() {
        assertEquals(500, anchoredScrollY(120, 120, 500, 1400))
    }

    @Test fun `a partly visible card can be anchored by its offscreen top edge`() {
        assertEquals(580, anchoredScrollY(-60, 20, 500, 1000))
    }

    @Test fun `new cards are shown normally while at the top`() {
        assertEquals(0, anchoredScrollY(30, 210, 0, 1000))
    }

    @Test fun `correction clamps to either end of the new scroll range`() {
        assertEquals(0, anchoredScrollY(200, -600, 500, 1000))
        assertEquals(600, anchoredScrollY(200, 600, 500, 600))
        assertEquals(0, anchoredScrollY(200, 600, 500, 0))
        assertEquals(0, anchoredScrollY(200, 600, 500, -20))
    }

    @Test fun `a platform clamp during layout still restores the previous screen position`() {
        // Content shrinks by 200; ScrollView already clamps 500 to 300 during layout.
        // The new viewport top thus remains 100: no second subtraction is needed.
        assertEquals(300, anchoredScrollY(100, 100, 300, 300, wasAtTop = false))
        // A clamp to zero does not imply that the owner was at the top before layout.
        assertEquals(80, anchoredScrollY(100, 180, 0, 300, wasAtTop = false))
    }

    @Test fun `rebasing after a correction makes repeated draws idempotent`() {
        val corrected = anchoredScrollY(420, 600, 500, 1000)
        assertEquals(corrected, anchoredScrollY(420, 420, corrected, 1000))
        assertEquals(500, anchoredScrollY(420, 240, corrected, 1000))
    }

    @Test fun `scrolling rebases the anchor before the next layout change`() {
        // After scrolling from 500 to 700 the button's recorded top is now 220.
        assertEquals(700, anchoredScrollY(220, 220, 700, 1400))
        assertEquals(880, anchoredScrollY(220, 400, 700, 1400))
        // Programmatic navigation to the top uses the same top exception.
        assertEquals(0, anchoredScrollY(20, 200, 0, 1400))
    }

    @Test fun `large coordinate differences cannot overflow before clamping`() {
        assertEquals(1000, anchoredScrollY(Int.MIN_VALUE, Int.MAX_VALUE, 500, 1000))
        assertEquals(0, anchoredScrollY(Int.MAX_VALUE, Int.MIN_VALUE, 500, 1000))
    }
}
