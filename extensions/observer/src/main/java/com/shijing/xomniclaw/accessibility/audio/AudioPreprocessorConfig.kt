package com.shijing.xomniclaw.accessibility.audio

/**
 * 录音预处理配置。
 *
 * 这里将“是否启用 AEC”“是否抓取回放参考音”“是否优先走 WebRTC/JNI”
 * 这些策略参数集中起来，避免 VoiceRecorderManager 直接感知底层实现细节。
 */
data class AudioPreprocessorConfig(
    val enabled: Boolean = true,
    val preferWebRtcAec: Boolean = true,
    val enablePlaybackCapture: Boolean = true,
    val enableSystemAecFallback: Boolean = true,
    val sampleRateHz: Int = 16_000,
    val frameSizeSamples: Int = 160,
    val fallbackSuppressRatio: Float = 0.72f,
    val fallbackCorrelationGate: Float = 0.18f
)
