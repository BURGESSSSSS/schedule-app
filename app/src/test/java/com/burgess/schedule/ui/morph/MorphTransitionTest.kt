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
