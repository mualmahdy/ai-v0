package com.example.presentation

import com.example.presentation.ui.shouldShowBottomNavigationBar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IME / INSETS (§4A-refine): the frame-perfect bottom-bar gate — the pure
 * decision that kills the persistent blank strip above the keyboard.
 *
 * The invariant under test: the compact bottom NavigationBar and the
 * composer's imePadding NEVER double-book the same screen strip. The bar
 * yields exactly when the keyboard has covered the bar's own
 * navigation-bar region (animated values, not a binary "ime arrived" flip),
 * and it returns the moment the region is uncovered again.
 */
class BottomBarImeGatingTest {

    // ---- Keyboard closed: the bar owns its region ----

    @Test
    fun `keyboard fully closed shows the bar in compact`() {
        assertTrue(
            shouldShowBottomNavigationBar(
                isCompact = true,
                imeBottomPx = 0,
                navigationBarBottomPx = 132
            )
        )
    }

    @Test
    fun `closed keyboard with a zero navigation-bar inset still shows the bar`() {
        // Some devices report 0 for the navigation-bar inset (gesture nav
        // fully hidden); the bar still has its own layout height and MUST
        // stay visible — a strict comparison would hide it forever.
        assertTrue(
            shouldShowBottomNavigationBar(
                isCompact = true,
                imeBottomPx = 0,
                navigationBarBottomPx = 0
            )
        )
    }

    @Test
    fun `keyboard closed never shows the bar outside compact`() {
        assertFalse(
            shouldShowBottomNavigationBar(
                isCompact = false,
                imeBottomPx = 0,
                navigationBarBottomPx = 132
            )
        )
    }

    // ---- Keyboard open: the bar yields its region ----

    @Test
    fun `open keyboard covering the navigation-bar region hides the bar`() {
        assertFalse(
            shouldShowBottomNavigationBar(
                isCompact = true,
                imeBottomPx = 940,
                navigationBarBottomPx = 132
            )
        )
    }

    @Test
    fun `open keyboard hides the bar even when the navigation inset is zero`() {
        assertFalse(
            shouldShowBottomNavigationBar(
                isCompact = true,
                imeBottomPx = 500,
                navigationBarBottomPx = 0
            )
        )
    }

    @Test
    fun `open keyboard hides the bar outside compact too`() {
        assertFalse(
            shouldShowBottomNavigationBar(
                isCompact = false,
                imeBottomPx = 940,
                navigationBarBottomPx = 132
            )
        )
    }

    // ---- Animating: no frame ever double-books the strip ----

    @Test
    fun `keyboard sliding in keeps the bar until it covers the bar region`() {
        // ime animates 0 -> 940. While the animated ime bottom is still
        // above the navigation-bar region (partially covering it), the bar
        // stays — the keyboard draws over it, so removing it early would
        // expose a bar-height blank strip ABOVE the still-animating
        // keyboard (the reported defect).
        assertTrue(
            shouldShowBottomNavigationBar(
                isCompact = true,
                imeBottomPx = 80,
                navigationBarBottomPx = 132
            )
        )
        // The exact covering frame: ime == navigationBars — the keyboard's
        // top edge sits exactly on the bar's region top, so the keyboard
        // already draws over the whole region and the bar yields (visually
        // identical on either side of the boundary).
        assertFalse(
            shouldShowBottomNavigationBar(
                isCompact = true,
                imeBottomPx = 132,
                navigationBarBottomPx = 132
            )
        )
        // One pixel past the covering frame: the keyboard owns the strip.
        assertFalse(
            shouldShowBottomNavigationBar(
                isCompact = true,
                imeBottomPx = 133,
                navigationBarBottomPx = 132
            )
        )
    }

    @Test
    fun `keyboard sliding out brings the bar back as soon as the region is uncovered`() {
        // ime animates 940 -> 0. The moment the animated ime bottom drops
        // below the navigation-bar region, the keyboard no longer draws
        // over the bar — the bar returns with no blank frame in between.
        assertTrue(
            shouldShowBottomNavigationBar(
                isCompact = true,
                imeBottomPx = 131,
                navigationBarBottomPx = 132
            )
        )
        // ... and stays back once fully closed.
        assertTrue(
            shouldShowBottomNavigationBar(
                isCompact = true,
                imeBottomPx = 0,
                navigationBarBottomPx = 132
            )
        )
    }

    // ---- Negative / degenerate inputs are honest, never crashy ----

    @Test
    fun `negative inset values never show the bar outside compact and never hide it inside`() {
        assertFalse(shouldShowBottomNavigationBar(isCompact = false, imeBottomPx = -1, navigationBarBottomPx = -1))
        assertTrue(shouldShowBottomNavigationBar(isCompact = true, imeBottomPx = -1, navigationBarBottomPx = 132))
    }
}
