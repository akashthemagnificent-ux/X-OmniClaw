package com.shijing.xomniclaw.accessibility.audio

import android.content.Context
import android.media.audiofx.AcousticEchoCanceler
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 观察层音频预处理入口。
 *
 * 优先顺序：
 * 1. 若 JNI 桥已接入真正 WebRTC AEC3，则走 native reverse/capture 流程。
 * 2. 否则启用回放捕获 + 轻量软件回声对冲。
 * 3. 再叠加系统 AcousticEchoCanceler 作为保底。
 */
class ObserverAudioPreprocessor(
    context: Context,
    private val config: AudioPreprocessorConfig,
    mediaProjectionProvider: () -> MediaProjection?
) : AudioFramePreprocessor {

    companion object {
        private const val TAG = "ObserverAudioPre"
    }

    private val appContext = context.applicationContext
    private val referenceCapturer = PlaybackReferenceCapturer(appContext, config, mediaProjectionProvider)
    private val fallbackCanceller = SimpleAdaptiveEchoCanceller(
        suppressRatio = config.fallbackSuppressRatio,
        correlationGate = config.fallbackCorrelationGate
    )

    private var nativeHandle: Long = 0L
    private var acousticEchoCanceler: AcousticEchoCanceler? = null

    override fun start(audioSessionId: Int) {
        if (!config.enabled) {
            return
        }

        if (config.enableSystemAecFallback && AcousticEchoCanceler.isAvailable()) {
            runCatching {
                acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
            }.onFailure {
                Log.w(TAG, "系统 AEC 启用失败: ${it.message}")
            }
        }

        if (config.preferWebRtcAec) {
            nativeHandle = WebRtcAecNativeBridge.create(config.sampleRateHz, config.frameSizeSamples)
        }

        val playbackStarted = referenceCapturer.start()
        Log.i(
            TAG,
            "音频预处理已启动: nativeReady=${WebRtcAecNativeBridge.isReady()}, playbackCapture=$playbackStarted, sdk=${Build.VERSION.SDK_INT}"
        )
    }

    override fun processCapturePcm(pcmBytes: ByteArray, sizeInBytes: Int): ByteArray {
        if (!config.enabled || sizeInBytes <= 1) {
            return pcmBytes.copyOf(sizeInBytes)
        }

        val captureShorts = pcm16BytesToShorts(pcmBytes, sizeInBytes)
        val referenceShorts = referenceCapturer.getLatestFrame()
        if (referenceShorts != null && referenceShorts.isNotEmpty()) {
            if (WebRtcAecNativeBridge.isReady() && nativeHandle != 0L) {
                WebRtcAecNativeBridge.pushReverseFrame(nativeHandle, referenceShorts)
            } else {
                fallbackCanceller.pushReverseFrame(referenceShorts)
            }
        }

        val processed = if (WebRtcAecNativeBridge.isReady() && nativeHandle != 0L) {
            chunkFrames(captureShorts) { frame ->
                WebRtcAecNativeBridge.processCaptureFrame(nativeHandle, frame)
            }
        } else {
            chunkFrames(captureShorts) { frame ->
                fallbackCanceller.cancelEcho(frame)
            }
        }
        return shortsToPcm16Bytes(processed)
    }

    override fun stop() {
        referenceCapturer.stop()
        fallbackCanceller.clear()
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        WebRtcAecNativeBridge.release(nativeHandle)
        nativeHandle = 0L
    }

    fun release() {
        stop()
        referenceCapturer.release()
    }

    private fun chunkFrames(input: ShortArray, processor: (ShortArray) -> ShortArray): ShortArray {
        val frameSize = config.frameSizeSamples.coerceAtLeast(1)
        if (input.isEmpty()) {
            return input
        }

        val output = ShortArray(input.size)
        var offset = 0
        while (offset < input.size) {
            val size = minOf(frameSize, input.size - offset)
            val frame = ShortArray(size)
            input.copyInto(frame, endIndex = offset + size, destinationOffset = 0, startIndex = offset)
            val processed = processor(frame)
            val safe = minOf(size, processed.size)
            processed.copyInto(output, destinationOffset = offset, endIndex = safe, startIndex = 0)
            if (safe < size) {
                frame.copyInto(output, destinationOffset = offset + safe, startIndex = safe, endIndex = size)
            }
            offset += size
        }
        return output
    }

    private fun pcm16BytesToShorts(bytes: ByteArray, sizeInBytes: Int): ShortArray {
        val safeSize = sizeInBytes - (sizeInBytes % 2)
        val shorts = ShortArray(safeSize / 2)
        ByteBuffer.wrap(bytes, 0, safeSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return shorts
    }

    private fun shortsToPcm16Bytes(shorts: ShortArray): ByteArray {
        val byteBuffer = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in shorts) {
            byteBuffer.putShort(sample)
        }
        return byteBuffer.array()
    }
}
