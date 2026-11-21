package yos.music.player.ui.widgets.effects.progressiveblur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class ProgressiveBlurMathTest {

    @Test
    fun mirroredTileCoordIsContinuousAndBoundedAtSplit() {
        val split = 0.50f
        assertEquals(0f, ProgressiveBlurMath.mirroredTileCoord(0f, split), 1e-5f)
        assertEquals(1f, ProgressiveBlurMath.mirroredTileCoord(split, split), 1e-5f)
        assertEquals(0f, ProgressiveBlurMath.mirroredTileCoord(1f, split), 1e-5f)

        val eps = 0.0005f
        val leftOfSplit = ProgressiveBlurMath.mirroredTileCoord(split - eps, split)
        val rightOfSplit = ProgressiveBlurMath.mirroredTileCoord(split + eps, split)
        assertEquals(leftOfSplit, rightOfSplit, 1e-4f)
        assertTrue(leftOfSplit > 0.99f)

        // Asymmetric split < 0.5f folds cleanly back without out-of-range clamping streaks.
        val narrowSplit = 0.35f
        for (i in 0..200) {
            val x = i / 200f
            val tx = ProgressiveBlurMath.mirroredTileCoord(x, narrowSplit)
            assertTrue("Out of bounds tx=$tx at x=$x", tx in 0f..1f)
        }
        assertEquals(1f, ProgressiveBlurMath.mirroredTileCoord(0.35f, narrowSplit), 1e-5f)
        assertEquals(0f, ProgressiveBlurMath.mirroredTileCoord(0.70f, narrowSplit), 1e-5f)
        assertTrue(ProgressiveBlurMath.mirroredTileCoord(0.85f, narrowSplit) > 0.4f)
    }

    @Test
    fun smoothstepAndSofterstepAreClampedAndStrictlyMonotonic() {
        assertEquals(0f, ProgressiveBlurMath.smoothstep(0.2f, 0.8f, 0.0f), 1e-5f)
        assertEquals(0f, ProgressiveBlurMath.smoothstep(0.2f, 0.8f, 0.2f), 1e-5f)
        assertEquals(0.5f, ProgressiveBlurMath.smoothstep(0.2f, 0.8f, 0.5f), 1e-5f)
        assertEquals(1f, ProgressiveBlurMath.smoothstep(0.2f, 0.8f, 0.8f), 1e-5f)
        assertEquals(1f, ProgressiveBlurMath.smoothstep(0.2f, 0.8f, 1.0f), 1e-5f)

        assertEquals(0f, ProgressiveBlurMath.softerstep(0.2f, 0.8f, 0.0f), 1e-5f)
        assertEquals(0f, ProgressiveBlurMath.softerstep(0.2f, 0.8f, 0.2f), 1e-5f)
        assertEquals(0.5f, ProgressiveBlurMath.softerstep(0.2f, 0.8f, 0.5f), 1e-5f)
        assertEquals(1f, ProgressiveBlurMath.softerstep(0.2f, 0.8f, 0.8f), 1e-5f)
        assertEquals(1f, ProgressiveBlurMath.softerstep(0.2f, 0.8f, 1.0f), 1e-5f)

        var prevSmooth = -1f
        var prevSofter = -1f
        for (i in 1 until 100) {
            val x = 0.2f + (0.6f * i / 100f)
            val s = ProgressiveBlurMath.smoothstep(0.2f, 0.8f, x)
            val sf = ProgressiveBlurMath.softerstep(0.2f, 0.8f, x)
            assertTrue("smoothstep not strictly increasing at x=$x", s > prevSmooth)
            assertTrue("softerstep not strictly increasing at x=$x", sf > prevSofter)
            prevSmooth = s
            prevSofter = sf
        }
    }

    @Test
    fun progressionAndScrimAlphaRespectEndpointsAndLag() {
        val cfg = ProgressiveBlurConfig(
            blurStart = 0.30f,
            blurEnd = 0.70f,
            curvePower = 1.0f,
            scrimMaxAlpha = 0.60f,
            scrimPower = 1.5f,
        )

        assertEquals(0f, ProgressiveBlurMath.progression(0.0f, cfg), 1e-5f)
        assertEquals(0f, ProgressiveBlurMath.progression(0.30f, cfg), 1e-5f)
        assertEquals(1f, ProgressiveBlurMath.progression(0.70f, cfg), 1e-5f)
        assertEquals(1f, ProgressiveBlurMath.progression(1.0f, cfg), 1e-5f)

        assertEquals(0f, ProgressiveBlurMath.scrimAlpha(0.29f, cfg), 1e-5f)
        assertEquals(0.60f, ProgressiveBlurMath.scrimAlpha(0.70f, cfg), 1e-5f)
        assertEquals(0.60f, ProgressiveBlurMath.scrimAlpha(1.0f, cfg), 1e-5f)

        var prevProg = 0f
        var prevScrim = 0f
        for (i in 1..40) {
            val axis = 0.30f + 0.40f * (i / 40f)
            val prog = ProgressiveBlurMath.progression(axis, cfg)
            val scrim = ProgressiveBlurMath.scrimAlpha(axis, cfg)
            assertTrue("progression decreased at $axis", prog >= prevProg)
            assertTrue("scrimAlpha decreased at $axis", scrim >= prevScrim)
            if (axis > 0.30f && axis < 0.70f) {
                val normalizedScrim = scrim / cfg.scrimMaxAlpha
                assertTrue("scrim should lag behind blur onset when scrimPower > 1", normalizedScrim < prog)
            }
            prevProg = prog
            prevScrim = scrim
        }
    }

    @Test
    fun progression2dCombinesXAndYFeatheringSmoothly() {
        val cfg = ProgressiveBlurConfig.defaultTabletLandscape()
        assertEquals(0f, ProgressiveBlurMath.progression2D(0f, 0f, cfg), 1e-5f)
        assertEquals(0f, ProgressiveBlurMath.progression2D(cfg.blurStart, cfg.blurStartY, cfg), 1e-5f)

        val midX = (cfg.blurStart + cfg.blurEnd) * 0.5f
        val midY = (cfg.blurStartY + cfg.blurEndY) * 0.5f
        val ux = ProgressiveBlurMath.axisProgression(midX, cfg.blurStart, cfg.blurEnd, cfg.curveType, cfg.curvePower)
        val uy = ProgressiveBlurMath.axisProgression(midY, cfg.blurStartY, cfg.blurEndY, cfg.curveType, cfg.curvePower)

        // Top edge (y=0) follows ux; left edge (x=0) follows uy.
        assertEquals(ux, ProgressiveBlurMath.progression2D(midX, 0f, cfg), 1e-5f)
        assertEquals(uy, ProgressiveBlurMath.progression2D(0f, midY, cfg), 1e-5f)

        // Bottom-right corner of the crisp zone combines smoothly via 1 - (1 - ux)(1 - uy) > max(ux, uy).
        val corner = ProgressiveBlurMath.progression2D(midX, midY, cfg)
        assertEquals(1f - (1f - ux) * (1f - uy), corner, 1e-5f)
        assertTrue(corner > max(ux, uy))
        assertEquals(1f, ProgressiveBlurMath.progression2D(1f, 1f, cfg), 1e-5f)
    }

    @Test
    fun resolveMirrorSplitsMaintainsExactSquareAspectAcrossFormFactors() {
        // Tablet Landscape 2800x2000 (coverSize = 0.65)
        val tabLand = ProgressiveBlurMath.resolveMirrorSplits(
            2800f,
            2000f,
            ProgressiveBlurConfig.defaultTabletLandscape(),
        )
        assertEquals(1300f / 2800f, tabLand.splitX, 1e-4f)
        assertEquals(0.65f, tabLand.splitY, 1e-4f)
        assertEquals(1.0f, (2800f * tabLand.splitX) / (2000f * tabLand.splitY), 1e-4f)

        // Tablet Portrait 2000x2800 (coverSize = 1.00)
        val tabPort = ProgressiveBlurMath.resolveMirrorSplits(
            2000f,
            2800f,
            ProgressiveBlurConfig.defaultTabletPortrait(),
        )
        assertEquals(1.0f, tabPort.splitX, 1e-5f)
        assertEquals(2000f / 2800f, tabPort.splitY, 1e-4f)
        assertEquals(1.0f, 2000f / (2800f * tabPort.splitY), 1e-4f)

        // Phone Landscape 2772x1280 (coverSize = 1.00)
        val phoneLand = ProgressiveBlurMath.resolveMirrorSplits(
            2772f,
            1280f,
            ProgressiveBlurConfig.defaultPhoneLandscape(),
        )
        assertEquals(1280f / 2772f, phoneLand.splitX, 1e-4f)
        assertEquals(1.0f, phoneLand.splitY, 1e-5f)
        assertEquals(1.0f, (2772f * phoneLand.splitX) / 1280f, 1e-4f)

        // Phone Portrait 1280x2772 (coverSize = 1.00)
        val phonePort = ProgressiveBlurMath.resolveMirrorSplits(
            1280f,
            2772f,
            ProgressiveBlurConfig.defaultPhonePortrait(),
        )
        assertEquals(1.0f, phonePort.splitX, 1e-5f)
        assertEquals(1280f / 2772f, phonePort.splitY, 1e-4f)
        assertEquals(1.0f, 1280f / (2772f * phonePort.splitY), 1e-4f)
    }

    @Test
    fun sanitizedClampsExtendedSevenLevelAndYAxisBounds() {
        val extreme2D = ProgressiveBlurConfig(
            orientationMode = ProgressiveOrientationMode.TOP_LEFT_2D,
            coverSize = 1.50f,
            blurStart = 0.85f,
            blurEnd = 0.80f,
            blurStartY = 0.88f,
            blurEndY = 0.85f,
            maxBlurLevel = 9.5f,
            ambientBlend = 1.2f,
        ).sanitized()

        assertEquals(0.95f, extreme2D.coverSize, 1e-5f)
        assertTrue(extreme2D.blurEnd >= extreme2D.blurStart + 0.02f)
        assertTrue(extreme2D.blurEndY >= extreme2D.blurStartY + 0.02f)
        assertEquals(7.0f, extreme2D.maxBlurLevel, 1e-5f)
        assertEquals(0.85f, extreme2D.ambientBlend, 1e-5f)

        val extreme1D = ProgressiveBlurConfig(
            orientationMode = ProgressiveOrientationMode.VERTICAL,
            coverSize = 0.10f,
        ).sanitized()
        assertEquals(0.65f, extreme1D.coverSize, 1e-5f)

        val extreme1DHigh = ProgressiveBlurConfig(
            orientationMode = ProgressiveOrientationMode.HORIZONTAL,
            coverSize = 2.00f,
        ).sanitized()
        assertEquals(1.25f, extreme1DHigh.coverSize, 1e-5f)
    }

    @Test
    fun pyramidDimensionsHalveAndClampAtOneAcrossSevenLevels() {
        val dims = ProgressiveBlurMath.pyramidDimensions(2800, 2000, levels = 7)
        assertEquals(
            listOf(
                1400 to 1000,
                700 to 500,
                350 to 250,
                175 to 125,
                87 to 62,
                43 to 31,
                21 to 15,
            ),
            dims,
        )

        val tiny = ProgressiveBlurMath.pyramidDimensions(3, 2, levels = 7)
        assertEquals(7, tiny.size)
        tiny.forEach { (w, h) ->
            assertTrue("Width must be >= 1, got $w", w >= 1)
            assertTrue("Height must be >= 1, got $h", h >= 1)
        }
        assertEquals(1 to 1, tiny.last())
    }

    @Test
    fun centeredOriginXAndProgression2dWithOriginPreserveSymmetryAndLeftDock() {
        val cfg = ProgressiveBlurConfig.defaultTabletLandscape()
        val splits = ProgressiveBlurMath.resolveMirrorSplits(2800f, 2000f, cfg)
        val splitX = splits.splitX

        val centeredOrigin = ProgressiveBlurMath.centeredOriginX(splitX, alignLeftProgress = 0f)
        val dockedOrigin = ProgressiveBlurMath.centeredOriginX(splitX, alignLeftProgress = 1f)
        assertEquals((1f - splitX) * 0.5f, centeredOrigin, 1e-5f)
        assertEquals(0f, dockedOrigin, 1e-5f)

        var prevOrigin = centeredOrigin + 0.01f
        for (step in 0..10) {
            val p = step / 10f
            val origin = ProgressiveBlurMath.centeredOriginX(splitX, p)
            assertTrue("centeredOriginX must decrease monotonically at p=$p", origin <= prevOrigin)
            prevOrigin = origin
        }

        // Moving origin from 0f (docked) to centeredOrigin monotonically ramps left-side progression.
        var prevLeftSample = -1f
        for (step in 0..10) {
            val origin = centeredOrigin * (step / 10f)
            val valLeft = ProgressiveBlurMath.progression2DWithOrigin(0.02f, 0.20f, cfg, splitX, origin)
            assertTrue(
                "Left progression should increase monotonically at origin=$origin ($valLeft < $prevLeftSample)",
                valLeft >= prevLeftSample,
            )
            prevLeftSample = valLeft
        }

        // When coverOriginX == 0f (split-pane docked left), matches progression2D across grid.
        for (xi in 0..10) {
            for (yi in 0..10) {
                val x = xi / 10f
                val y = yi / 10f
                assertEquals(
                    ProgressiveBlurMath.progression2D(x, y, cfg),
                    ProgressiveBlurMath.progression2DWithOrigin(x, y, cfg, splitX, 0f),
                    1e-5f,
                )
            }
        }

        // When centered in Album mode, crisp center stays 0f and left/right progression is symmetric.
        assertEquals(
            0f,
            ProgressiveBlurMath.progression2DWithOrigin(0.5f, 0.2f, cfg, splitX, centeredOrigin),
            1e-5f,
        )
        val offsets = listOf(0.05f, 0.15f, 0.25f, 0.35f, 0.45f)
        for (y in listOf(0.1f, 0.4f, 0.65f, 0.9f)) {
            for (d in offsets) {
                val left = ProgressiveBlurMath.progression2DWithOrigin(
                    0.5f - d,
                    y,
                    cfg,
                    splitX,
                    centeredOrigin,
                )
                val right = ProgressiveBlurMath.progression2DWithOrigin(
                    0.5f + d,
                    y,
                    cfg,
                    splitX,
                    centeredOrigin,
                )
                assertEquals(
                    "Asymmetric horizontal feathering at d=$d, y=$y",
                    left,
                    right,
                    1e-4f,
                )
            }
        }
    }

    @Test
    fun mirroredTileCoordHandlesNegativeShiftedOriginContinuously() {
        val cfg = ProgressiveBlurConfig.defaultTabletLandscape()
        val splitX = ProgressiveBlurMath.resolveMirrorSplits(2800f, 2000f, cfg).splitX
        val origin = ProgressiveBlurMath.centeredOriginX(splitX, alignLeftProgress = 0f)

        assertEquals(0f, ProgressiveBlurMath.mirroredTileCoord(origin - origin, splitX), 1e-5f)
        val eps = 0.0005f
        val leftOfOrigin = ProgressiveBlurMath.mirroredTileCoord((origin - eps) - origin, splitX)
        val rightOfOrigin = ProgressiveBlurMath.mirroredTileCoord((origin + eps) - origin, splitX)
        assertEquals(leftOfOrigin, rightOfOrigin, 1e-4f)

        for (i in 0..200) {
            val x = i / 200f
            val tx = ProgressiveBlurMath.mirroredTileCoord(x - origin, splitX)
            assertTrue("Out-of-bounds shifted mirror tx=$tx at x=$x", tx in 0f..1f)
        }
    }

    @Test
    fun elongatedTabletLandscapeCentersDockedCoverAboveControlsAndProtectsMirrorSeams() {
        val cfg = ProgressiveBlurConfig.defaultTabletLandscape()
        assertEquals(0.65f, cfg.coverSize, 1e-5f)
        assertEquals(0.30f, cfg.blurStart, 1e-5f)
        assertEquals(0.59f, cfg.blurEnd, 1e-5f)
        assertEquals(0.38f, cfg.blurStartY, 1e-5f)
        assertEquals(0.80f, cfg.blurEndY, 1e-5f)
        assertEquals(5.65f, cfg.maxBlurLevel, 1e-5f)
        assertEquals(0.21f, cfg.ambientBlend, 1e-5f)
        assertEquals(0.22f, cfg.scrimMaxAlpha, 1e-5f)
        assertEquals(1.28f, cfg.blurSaturation, 1e-5f)

        // 4:3 and 7:5 remain flush-left when docked.
        val split43 = ProgressiveBlurMath.resolveMirrorSplits(2048f, 1536f, cfg).splitX
        val split75 = ProgressiveBlurMath.resolveMirrorSplits(2800f, 2000f, cfg).splitX
        assertEquals(0f, ProgressiveBlurMath.dockedOriginX(split43), 1e-5f)
        assertEquals(0f, ProgressiveBlurMath.dockedOriginX(split75), 1e-5f)

        val (refStart, refEnd) = ProgressiveBlurMath.resolveHorizontalBlurEdges(split75, cfg)
        assertEquals(0.30f, refStart, 1e-4f)
        assertEquals(0.59f, refEnd, 1e-4f)

        // 16:10, 16:9, 20:9 center the docked cover at 0.225f and preserve symmetric seam blur.
        val wideViewports = listOf(
            2560f to 1600f, // 16:10
            2560f to 1440f, // 16:9
            2800f to 1260f, // 20:9
        )
        for ((w, h) in wideViewports) {
            val splitX = ProgressiveBlurMath.resolveMirrorSplits(w, h, cfg).splitX
            val dockedOrigin = ProgressiveBlurMath.dockedOriginX(splitX)
            assertTrue("Expected positive dockedOrigin for ${w}x$h, got $dockedOrigin", dockedOrigin > 0.01f)
            assertEquals(
                ProgressiveBlurMath.TABLET_SPLIT_CONTROLS_CENTER_X,
                dockedOrigin + splitX * 0.5f,
                1e-5f,
            )
            assertEquals(dockedOrigin, ProgressiveBlurMath.centeredOriginX(splitX, 1f), 1e-5f)

            val (effStart, effEnd) = ProgressiveBlurMath.resolveHorizontalBlurEdges(splitX, cfg)
            assertTrue("effStart=$effStart must be < splitX=$splitX on ${w}x$h", effStart < splitX)
            assertTrue("splitX=$splitX must be < effEnd=$effEnd on ${w}x$h", splitX < effEnd)

            // Center stays crisp.
            assertEquals(
                0f,
                ProgressiveBlurMath.progression2DWithOrigin(
                    ProgressiveBlurMath.TABLET_SPLIT_CONTROLS_CENTER_X,
                    0.15f,
                    cfg,
                    splitX,
                    dockedOrigin,
                ),
                1e-5f,
            )

            // Left and right sides of the docked cover are symmetric around 0.225f.
            val offsets = listOf(0.05f, 0.12f, 0.18f, 0.22f)
            for (y in listOf(0.10f, 0.45f, 0.80f)) {
                for (d in offsets) {
                    val left = ProgressiveBlurMath.progression2DWithOrigin(
                        ProgressiveBlurMath.TABLET_SPLIT_CONTROLS_CENTER_X - d,
                        y,
                        cfg,
                        splitX,
                        dockedOrigin,
                    )
                    val right = ProgressiveBlurMath.progression2DWithOrigin(
                        ProgressiveBlurMath.TABLET_SPLIT_CONTROLS_CENTER_X + d,
                        y,
                        cfg,
                        splitX,
                        dockedOrigin,
                    )
                    assertEquals("Expected symmetric docked feather at ${w}x$h d=$d y=$y", left, right, 1e-4f)
                }
            }

            // Both left and right mirror seams have strong blur (> 0.5f) so neither seam looks sharp.
            val leftSeamBlur = ProgressiveBlurMath.progression2DWithOrigin(dockedOrigin, 0.15f, cfg, splitX, dockedOrigin)
            val rightSeamBlur = ProgressiveBlurMath.progression2DWithOrigin(dockedOrigin + splitX, 0.15f, cfg, splitX, dockedOrigin)
            assertTrue("Left mirror seam must be blurred ($leftSeamBlur) on ${w}x$h", leftSeamBlur > 0.5f)
            assertTrue("Right mirror seam must be blurred ($rightSeamBlur) on ${w}x$h", rightSeamBlur > 0.5f)
            assertEquals(leftSeamBlur, rightSeamBlur, 1e-4f)
        }
    }

    @Test
    fun phoneLandscapeDefaultConfigMatchesTunedPreset() {
        val cfg = ProgressiveBlurConfig.defaultPhoneLandscape()
        assertEquals(ProgressiveOrientationMode.HORIZONTAL, cfg.orientationMode)
        assertEquals(ProgressiveBlurMode.TRUE_PROGRESSIVE, cfg.blurMode)
        assertEquals(ProgressiveCurveType.SOFTERSTEP, cfg.curveType)
        assertEquals(1.00f, cfg.coverSize, 1e-5f)
        assertEquals(0.33f, cfg.blurStart, 1e-5f)
        assertEquals(0.57f, cfg.blurEnd, 1e-5f)
        assertEquals(4.99f, cfg.maxBlurLevel, 1e-5f)
        assertEquals(0.49f, cfg.ambientBlend, 1e-5f)
        assertEquals(0.83f, cfg.curvePower, 1e-5f)
        assertEquals(0.11f, cfg.scrimMaxAlpha, 1e-5f)
        assertEquals(0.96f, cfg.scrimPower, 1e-5f)
        assertEquals(1.17f, cfg.blurSaturation, 1e-5f)
        assertEquals(false, cfg.showDebugGuides)
        assertEquals(cfg, cfg.sanitized())
    }
}
