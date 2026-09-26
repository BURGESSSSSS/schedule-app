package com.burgess.schedule.ui.morph

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.burgess.schedule.ui.theme.CoursePalette
import com.burgess.schedule.ui.theme.LightAppColors
import com.burgess.schedule.ui.theme.courseTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphMathTest {
    @Test fun geometryEndpointsAndMonotonicity() {
        assertEquals(0f, MorphMath.geometryProgress(0f), 0f)
        assertEquals(1f, MorphMath.geometryProgress(1f), 0f)
        var previous = -1f
        for (i in 0..100) {
            val value = MorphMath.geometryProgress(i / 100f)
            assertTrue(value >= previous)
            previous = value
        }
    }

    @Test fun titleHandoffIsComplementaryAndGeometrySettlesBeforeCrossfade() {
        for (i in 0..1000) {
            val t = i / 1000f
            val source = MorphMath.sourceFaceAlpha(t)
            val detail = MorphMath.detailAlpha(t)
            assertEquals(1f, source + detail, .0001f)
            assertFalse(MorphMath.bothNearlyInvisible(t))
        }
        assertEquals(1f, MorphMath.titleGeometryProgress(.32f), .0001f)
        assertEquals(0f, MorphMath.sourceBodyAlpha(.14f), .0001f)
        assertEquals(0f, MorphMath.detailBodyAlpha(.45f), .0001f)
    }

    @Test fun geometryInterpolatesBoundsWithoutChangingLayerSizeMath() {
        val source = Rect(10f, 20f, 110f, 80f)
        val target = Rect(30f, 300f, 390f, 660f)
        assertEquals(source, MorphMath.interpolateRect(source, target, 0f))
        assertEquals(target, MorphMath.interpolateRect(source, target, 1f))
    }

    @Test fun morphMovementStartsImmediatelyAndDeceleratesWithoutOvershoot() {
        val samples = (0..100).map { step ->
            MorphMath.segmentGeometryProgress(step / 100f, 0f, 1f, 0f, 1f)
        }
        assertEquals(0f, samples.first(), 0f)
        assertEquals(1f, samples.last(), 0f)
        assertTrue(samples[10] > .1f)
        assertTrue(samples[10] - samples[0] > samples[100] - samples[90])
        assertTrue(samples.all { it in 0f..1f })
    }

    @Test fun closeAndMidAnimationReverseStartAtTheirCapturedVisualPosition() {
        val openAtMid = MorphMath.segmentGeometryProgress(.42f, 0f, 1f, 0f, 1f)
        val closeAtStart = MorphMath.segmentGeometryProgress(
            value = .42f,
            segmentStart = .42f,
            segmentEnd = 0f,
            visualStart = openAtMid,
            visualEnd = 0f
        )
        assertEquals(openAtMid, closeAtStart, .0001f)

        val laterClose = MorphMath.segmentGeometryProgress(
            value = .18f,
            segmentStart = .42f,
            segmentEnd = 0f,
            visualStart = openAtMid,
            visualEnd = 0f
        )
        assertTrue(laterClose < closeAtStart)

        val closeAtMid = MorphMath.segmentGeometryProgress(.5f, 1f, 0f, 1f, 0f)
        val reverseAtStart = MorphMath.segmentGeometryProgress(
            value = .5f,
            segmentStart = .5f,
            segmentEnd = 1f,
            visualStart = closeAtMid,
            visualEnd = 1f
        )
        assertEquals(closeAtMid, reverseAtStart, .0001f)
    }
}

class MorphControllerTest {
    @Test fun sourceColourIsStableAtColourCardEndpoint() {
        val (fill, sourceForeground) = courseTint(CoursePalette[1], LightAppColors)
        assertFalse(MorphMath.useSourceInkFallback(fill, sourceForeground, LightAppColors.ink))
    }

    @Test fun sourceFallsBackOnlyWhenBlackToWhiteMorphMakesOriginalUnreadable() {
        assertFalse(MorphMath.useSourceInkFallback(Color.Black, Color.White, Color.Black))
        assertTrue(MorphMath.useSourceInkFallback(Color.Gray, Color.White, Color.Black))
        assertTrue(MorphMath.useSourceInkFallback(Color.White, Color.White, Color.Black))
    }

    @Test fun controllerGateKeepsSessionUntilOneCompletedAction() {
        val gate = MorphSessionGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        assertTrue(gate.active)
        assertTrue(gate.beginExit(MorphExitAction.Edit))
        assertFalse(gate.beginExit(MorphExitAction.Delete))
        assertEquals(MorphExitAction.Edit, gate.finishExit())
        assertFalse(gate.active)
        assertNull(gate.finishExit())
    }
}
