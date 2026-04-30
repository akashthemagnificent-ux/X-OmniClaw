package com.shijing.xomniclaw.accessibility.audio

import android.util.Log

/**
 * WebRTC AEC3 的 JNI 桥接壳。
 *
 * 当前仓库先把 Kotlin -> JNI -> Native 的通道打通，后续若补入真正的 WebRTC
 * modules/audio_processing，只需要替换 native 实现，不需要再改上层录音流程。
 */
object WebRtcAecNativeBridge {
    private const val TAG = "WebRtcAecBridge"

    private val libraryLoaded: Boolean = runCatching {
        System.loadLibrary("xomniclaw_aec")
        true
    }.onFailure {
        Log.w(TAG, "未加载到 xomniclaw_aec，回退到 Kotlin AEC: ${it.message}")
    }.getOrDefault(false)

    fun isReady(): Boolean {
        return libraryLoaded && nativeHasAec3Support()
    }

    fun create(sampleRateHz: Int, frameSizeSamples: Int): Long {
        if (!libraryLoaded) {
            return 0L
        }
        return nativeCreate(sampleRateHz, frameSizeSamples)
    }

    fun pushReverseFrame(handle: Long, reverseFrame: ShortArray) {
        if (!libraryLoaded || handle == 0L || reverseFrame.isEmpty()) {
            return
        }
        nativePushReverseFrame(handle, reverseFrame)
    }

    fun processCaptureFrame(handle: Long, captureFrame: ShortArray): ShortArray {
        if (!libraryLoaded || handle == 0L || captureFrame.isEmpty()) {
            return captureFrame
        }
        return nativeProcessCaptureFrame(handle, captureFrame)
    }

    fun release(handle: Long) {
        if (!libraryLoaded || handle == 0L) {
            return
        }
        nativeRelease(handle)
    }

    private external fun nativeHasAec3Support(): Boolean
    private external fun nativeCreate(sampleRateHz: Int, frameSizeSamples: Int): Long
    private external fun nativePushReverseFrame(handle: Long, reverseFrame: ShortArray)
    private external fun nativeProcessCaptureFrame(handle: Long, captureFrame: ShortArray): ShortArray
    private external fun nativeRelease(handle: Long)
}
