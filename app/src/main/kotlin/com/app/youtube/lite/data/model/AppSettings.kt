package com.app.youtube.lite.data.model

import androidx.compose.runtime.Immutable

/** Quality ceiling for stream selection. */
enum class QualityCap(val label: String, val maxHeight: Int) {
    AUTO("Auto", Int.MAX_VALUE),
    Q1080("1080p", 1080),
    Q720("720p", 720),
    Q480("480p", 480),
    Q360("360p", 360),
    Q240("240p", 240),
}

/**
 * User-tunable performance policy.
 *
 * Every field here maps to a concrete runtime behaviour — nothing is decorative:
 * `preferH264` swaps the codec ladder (hardware AVC decoders are the most power-efficient on
 * almost every SoC), `backgroundAudio` decides whether the video renderer is disabled when the
 * app leaves the foreground, and `maxBufferMegabytes` is fed straight into
 * `LowRamLoadControl`.
 */
@Immutable
data class AppSettings(
    val qualityCap: QualityCap = QualityCap.AUTO,
    val preferH264: Boolean = true,
    val allowAv1: Boolean = false,
    val allowVp9: Boolean = true,
    val backgroundAudio: Boolean = true,
    val autoplayNext: Boolean = true,
    val dataSaver: Boolean = false,
    val highRefreshRate: Boolean = true,
    val maxBufferMegabytes: Int = 20,
    val doubleTapSeekSeconds: Int = 10,
) {
    companion object {
        val Default = AppSettings()
    }
}
