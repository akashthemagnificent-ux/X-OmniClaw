package com.shijing.xomniclaw.accessibility.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 抓取系统正在播放的媒体音频，作为 AEC 的 far-end 参考信号。
 *
 * Android 10+ 且用户已授权 MediaProjection 时，这条链路才能真正工作。
 */
class PlaybackReferenceCapturer(
    private val context: Context,
    private val config: AudioPreprocessorConfig,
    private val mediaProjectionProvider: () -> MediaProjection?
) {
    companion object {
        private const val TAG = "PlaybackReference"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playbackRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    private var latestFrame: ShortArray? = null

    fun start(): Boolean {
        if (!config.enablePlaybackCapture || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        if (captureJob != null) {
            return true
        }

        val projection = mediaProjectionProvider.invoke() ?: run {
            Log.i(TAG, "MediaProjection 未就绪，跳过回放参考音抓取")
            return false
        }

        return try {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(config.sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                config.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(config.frameSizeSamples * 8)

            playbackRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()

            if (playbackRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "回放捕获 AudioRecord 初始化失败")
                playbackRecord?.release()
                playbackRecord = null
                return false
            }

            playbackRecord?.startRecording()
            captureJob = scope.launch {
                val readBuffer = ByteArray(minBufferSize)
                while (isActive) {
                    val read = playbackRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                    if (read > 1) {
                        latestFrame = pcm16BytesToShorts(readBuffer, read)
                    }
                }
            }
            Log.i(TAG, "回放参考音抓取已启动")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "启动回放参考音抓取失败: ${t.message}")
            stop()
            false
        }
    }

    fun getLatestFrame(): ShortArray? {
        return latestFrame?.copyOf()
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        latestFrame = null
        try {
            playbackRecord?.stop()
        } catch (_: Throwable) {
        }
        playbackRecord?.release()
        playbackRecord = null
    }

    fun release() {
        stop()
        scope.cancel()
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
}
