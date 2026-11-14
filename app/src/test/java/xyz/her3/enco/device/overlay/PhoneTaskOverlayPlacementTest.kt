package xyz.her3.enco.device.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTaskOverlayPlacementTest {
    private val area = OverlaySafeArea(left = 24, top = 60, right = 1080, bottom = 2200)

    @Test
    fun placesBallAndCapsuleAgainstTheSameSide() {
        val left = OverlayDock(OverlayDockSide.LEFT, 0.25f)
        val right = OverlayDock(OverlayDockSide.RIGHT, 0.25f)

        assertEquals(OverlayOffset(24, 583), PhoneTaskOverlayPlacement.place(area, 48, 48, left))
        assertEquals(OverlayOffset(24, 583), PhoneTaskOverlayPlacement.place(area, 320, 48, left))
        assertEquals(OverlayOffset(1032, 583), PhoneTaskOverlayPlacement.place(area, 48, 48, right))
        assertEquals(OverlayOffset(760, 583), PhoneTaskOverlayPlacement.place(area, 320, 48, right))
    }

    @Test
    fun releaseUsesWindowCenterAndBoundedVerticalFraction() {
        val left = PhoneTaskOverlayPlacement.release(area, 100, 50, -500, -100)
        val right = PhoneTaskOverlayPlacement.release(area, 100, 50, 2000, 5000)
        val middle = PhoneTaskOverlayPlacement.release(OverlaySafeArea(0, 0, 100, 200), 100, 50, 0, 40)

        assertEquals(OverlayDockSide.LEFT, left.side)
        assertEquals(0f, left.verticalFraction)
        assertEquals(OverlayDockSide.RIGHT, right.side)
        assertEquals(1f, right.verticalFraction)
        assertEquals(OverlayDockSide.RIGHT, middle.side)
        assertTrue(middle.verticalFraction in 0f..1f)
    }

    @Test
    fun clampsDraggingInsideAsymmetricInsets() {
        val insetArea = OverlaySafeArea(left = 40, top = 100, right = 1000, bottom = 1800)

        assertEquals(
            OverlayOffset(40, 100),
            PhoneTaskOverlayPlacement.clamp(insetArea, 120, 60, -20, 0),
        )
        assertEquals(
            OverlayOffset(880, 1740),
            PhoneTaskOverlayPlacement.clamp(insetArea, 120, 60, 1100, 1900),
        )
    }

    @Test
    fun dockRoundTripDoesNotAccumulateOffset() {
        val dock = PhoneTaskOverlayPlacement.release(area, 320, 48, 700, 700)
        val capsule = PhoneTaskOverlayPlacement.place(area, 320, 48, dock)
        val ball = PhoneTaskOverlayPlacement.place(area, 48, 48, dock)
        val capsuleAgain = PhoneTaskOverlayPlacement.place(area, 320, 48, dock)

        assertEquals(OverlayDockSide.RIGHT, dock.side)
        assertEquals(OverlayOffset(760, 700), capsule)
        assertEquals(OverlayOffset(1032, 700), ball)
        assertEquals(capsule, capsuleAgain)
    }

    @Test
    fun rotationKeepsSideAndRelativeHeight() {
        val portrait = OverlaySafeArea(0, 80, 1080, 2240)
        val landscape = OverlaySafeArea(0, 40, 2240, 1040)
        val dock = PhoneTaskOverlayPlacement.release(portrait, 48, 48, 1000, 600)
        val original = PhoneTaskOverlayPlacement.place(portrait, 48, 48, dock)

        val rotated = PhoneTaskOverlayPlacement.place(landscape, 48, 48, dock)
        val restored = PhoneTaskOverlayPlacement.place(portrait, 48, 48, dock)

        assertEquals(OverlayOffset(2192, 274), rotated)
        assertEquals(original, restored)
    }

    @Test
    fun oversizedWindowFallsBackToSafeAreaOrigin() {
        val zeroHeight = OverlaySafeArea(10, 20, 110, 20)

        assertEquals(
            OverlayOffset(10, 20),
            PhoneTaskOverlayPlacement.clamp(zeroHeight, 200, 80, 50, 50),
        )
        assertEquals(
            OverlayOffset(10, 20),
            PhoneTaskOverlayPlacement.place(zeroHeight, 200, 80, OverlayDock(OverlayDockSide.RIGHT, 1f)),
        )
        assertEquals(0f, PhoneTaskOverlayPlacement.release(zeroHeight, 200, 80, 50, 50).verticalFraction)
    }
}
