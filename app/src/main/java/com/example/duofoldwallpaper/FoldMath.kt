package com.example.duofoldwallpaper

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

object FoldMath {

    const val DEMO_CYCLE_DURATION = 8.6f
    const val DEMO_HOLD_FLAT_END = 1.2f
    const val DEMO_FOLD_END = 4.3f
    const val DEMO_HOLD_CLOSED_END = 5.5f
    private const val DEMO_FOLD_SPAN = DEMO_FOLD_END - DEMO_HOLD_FLAT_END
    private const val DEMO_UNFOLD_SPAN = DEMO_CYCLE_DURATION - DEMO_HOLD_CLOSED_END

    data class UvWindow(
        val offsetX: Float,
        val offsetY: Float,
        val scaleX: Float,
        val scaleY: Float
    )

    /**
     * Exact aspect-fill cover UV calculation preventing any black bars or letterboxing.
     */
    fun computeCoverUvWindow(
        canvasWidth: Float,
        canvasHeight: Float,
        config: DeviceConfig
    ): UvWindow {
        val hinge = config.hingeRatio.coerceIn(0f, 0.99f)
        val availableUv = 1f - hinge

        val regionAspect = (availableUv * config.innerWidthPx) / max(config.innerHeightPx.toFloat(), 1f)
        val coverAspect = canvasWidth / max(canvasHeight, 1f)

        val scaleX: Float
        val scaleY: Float
        if (coverAspect < regionAspect) {
            scaleY = 1f
            scaleX = availableUv * (coverAspect / regionAspect)
        } else {
            scaleX = availableUv
            scaleY = regionAspect / coverAspect
        }

        val sx = scaleX.coerceIn(0.0001f, availableUv)
        val sy = scaleY.coerceIn(0.0001f, 1f)
        val offsetX = hinge
        val offsetY = ((1f - sy) * 0.5f).coerceIn(0f, 1f - sy)

        return UvWindow(offsetX = offsetX, offsetY = offsetY, scaleX = sx, scaleY = sy)
    }

    fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun demoAngleDegrees(phase: Float): Float {
        return when {
            phase < DEMO_HOLD_FLAT_END -> 180f
            phase < DEMO_FOLD_END -> {
                90f * (1f + cos((phase - DEMO_HOLD_FLAT_END) / DEMO_FOLD_SPAN * PI.toFloat()))
            }
            phase < DEMO_HOLD_CLOSED_END -> 0f
            else -> {
                90f * (1f - cos((phase - DEMO_HOLD_CLOSED_END) / DEMO_UNFOLD_SPAN * PI.toFloat()))
            }
        }
    }

    fun demoPhaseForAngle(currentDegrees: Float): Float {
        return DEMO_HOLD_FLAT_END +
            acos((2f * currentDegrees / 180f - 1f).coerceIn(-1f, 1f)).toFloat() / PI.toFloat() * DEMO_FOLD_SPAN
    }
}