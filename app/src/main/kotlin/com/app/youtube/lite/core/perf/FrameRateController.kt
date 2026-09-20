package com.app.youtube.lite.core.perf

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.app.youtube.lite.core.util.L

/**
 * Keeps the display in step with the video.
 *
 * Most phones ship a 120 Hz panel that drops to 60 Hz (or lower) when the UI is idle. Two things
 * go wrong when a video is playing on such a panel and nobody tells the system otherwise:
 *
 *  • a 60 fps stream on a panel that decided to sit at 90 Hz or 120 Hz produces uneven frame
 *    pacing — visible as a subtle judder, because the surface is being latched on a cadence that
 *    does not divide evenly into the content's;
 *  • the inverse: a 24/30 fps video pinning a 120 Hz panel wastes power for nothing.
 *
 * So the controller picks the panel mode whose refresh rate is the closest *integer multiple* of
 * the video's frame rate (never lower than the content), preferring a mode whose physical
 * resolution matches the video — upscaling a 1080p stream onto a 1440p mode costs GPU bandwidth
 * for no visible gain.
 *
 * [setSustainedPerformanceMode] is the second half of the story: it asks the platform to hold the
 * CPU/GPU at a steady (lower) clock instead of boosting and then thermally throttling, which is
 * what turns a long 4K video on a warm phone into a stutter fest. It is only used when the caller
 * decides the device is under pressure (low-RAM device, battery saver, thermal status).
 *
 * Every request here is *released*: [release] restores the window's own defaults, so the app
 * never leaves the display pinned to a mode after playback ends.
 */
class FrameRateController(private val activity: Activity) {

    private var requestedModeId = -1
    private var requestedRefreshRate = 0f
    private var sustainedPerformance = false

    /** Chooses the best panel mode for a video of `fps` frames per second at the given size. */
    fun matchVideo(width: Int, height: Int, fps: Float, allowHighRefreshRate: Boolean = true) {
        val window = activity.window ?: return
        val display: Display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display ?: return
        } else {
            @Suppress("DEPRECATION")
            window.windowManager.defaultDisplay
        }

        val target = if (fps > 0f) fps else DEFAULT_FPS
        val modes = runCatching { display.supportedModes }.getOrNull()
        if (modes.isNullOrEmpty()) {
            // Nothing to choose from: fall back to the coarse (API 23) refresh-rate hint.
            if (allowHighRefreshRate) {
                fallbackRefreshRate(window.attributes, target)
            }
            return
        }

        var bestMode: Display.Mode? = null
        var bestScore = Float.MAX_VALUE
        for (mode in modes) {
            val refresh = mode.refreshRate
            if (refresh <= 0f) continue
            // A rate below the content's frame rate can never be correct.
            if (refresh < target - 1f) continue
            if (!allowHighRefreshRate && refresh > 61f) continue

            // Prefer a refresh rate that divides the content evenly: 60 fps on a 60 Hz or 120 Hz
            // panel is perfect, on a 90 Hz panel it is 1.5:1 and judders.
            val ratio = refresh / target
            val remainder = ratio - kotlin.math.floor(ratio)
            val pacingPenalty = minOf(remainder, 1f - remainder) * 10f

            // Prefer a mode whose resolution matches the video rather than upscaling it.
            val resolutionPenalty = when {
                mode.physicalHeight == height -> 0f
                mode.physicalHeight > height -> (mode.physicalHeight - height) / 2160f
                else -> 0.5f
            }
            val score = pacingPenalty + resolutionPenalty
            if (score < bestScore) {
                bestScore = score
                bestMode = mode
            }
        }

        val chosen = bestMode
        if (chosen == null) {
            fallbackRefreshRate(window.attributes, target)
            return
        }
        requestedModeId = chosen.modeId
        val attributes = window.attributes
        attributes.preferredDisplayModeId = chosen.modeId
        // Keep the coarse hint in step; some vendor ROMs honour only one of the two.
        attributes.preferredRefreshRate = chosen.refreshRate
        L.d("FrameRate") {
            "video ${width}x$height@$fps → mode ${chosen.modeId} ${chosen.physicalWidth}x" +
                "${chosen.physicalHeight}@${chosen.refreshRate}Hz"
        }
    }

    /** Mode selection is unavailable (no modes reported): ask for the highest sensible rate. */
    private fun fallbackRefreshRate(attributes: WindowManager.LayoutParams, target: Float) {
        val rate = if (target > 61f) target else DISPLAY_120
        requestedRefreshRate = rate
        attributes.preferredRefreshRate = rate
        L.d("FrameRate") { "no display modes reported; requested ${rate}Hz" }
    }

    /**
     * Steady clocks instead of boost-then-throttle. The platform only honours this when the
     * window is focused and the device supports it; requesting it is harmless otherwise.
     */
    fun setSustainedPerformanceMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (sustainedPerformance == enabled) return
        sustainedPerformance = enabled
        runCatching { activity.window.setSustainedPerformanceMode(enabled) }
            .onFailure { L.d("FrameRate") { "sustained performance mode unsupported: $it" } }
    }

    /** Hands the display back to the system. Always called when playback ends. */
    fun release() {
        val window = activity.window ?: return
        if (requestedModeId >= 0) {
            window.attributes.preferredDisplayModeId = 0
            requestedModeId = -1
        }
        if (requestedRefreshRate != 0f) {
            window.attributes.preferredRefreshRate = 0f
            requestedRefreshRate = 0f
        }
        if (sustainedPerformance) {
            setSustainedPerformanceMode(false)
        }
    }

    /**
     * Recommended for this device: true when the platform says memory is tight or the battery
     * saver is on — the two situations where smooth-and-steady beats fast-and-throttled.
     */
    fun shouldUseSustainedPerformance(): Boolean {
        val manager = activity.getSystemService(android.content.Context.POWER_SERVICE)
            as? android.os.PowerManager
        val powerSaver = manager?.isPowerSaveMode == true
        val lowRam = (activity.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager)?.isLowRamDevice == true
        return powerSaver || lowRam
    }

    private companion object {
        const val DEFAULT_FPS = 30f
        const val DISPLAY_120 = 120f
    }
}
