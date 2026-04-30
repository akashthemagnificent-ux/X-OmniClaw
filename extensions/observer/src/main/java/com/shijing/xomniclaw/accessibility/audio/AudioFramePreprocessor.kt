package com.shijing.xomniclaw.accessibility.audio

/**
 * 统一的音频预处理接口。
 *
 * 录音链路只依赖这个抽象，后续无论底层是系统 AEC、JNI 桥还是完整 WebRTC AEC3，
 * 都不需要再次改动 VoiceRecorderManager 的主流程。
 */
interface AudioFramePreprocessor {
    fun start(audioSessionId: Int)

    /**
     * 处理一段 PCM16 Little Endian 的麦克风数据。
     * 返回值长度与输入保持一致，便于直接复用原有写缓冲流程。
     */
    fun processCapturePcm(pcmBytes: ByteArray, sizeInBytes: Int): ByteArray

    fun stop()
}
