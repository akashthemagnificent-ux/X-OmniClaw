package com.shijing.xomniclaw.accessibility.audio

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 纯 Kotlin 的轻量回声对冲器。
 *
 * 它不是完整 WebRTC AEC3，但在没有原生 AEC3 库时，至少可以利用播放参考音做一层
 * 基于相关性的延迟估计与能量对冲，明显降低“外放再次被麦克风录进去”的问题。
 */
class SimpleAdaptiveEchoCanceller(
    private val suppressRatio: Float,
    private val correlationGate: Float,
    private val historyFrames: Int = 12
) {
    private val reverseHistory = ArrayDeque<ShortArray>()

    fun clear() {
        reverseHistory.clear()
    }

    fun pushReverseFrame(frame: ShortArray) {
        if (frame.isEmpty()) {
            return
        }
        reverseHistory.addLast(frame.copyOf())
        while (reverseHistory.size > historyFrames) {
            reverseHistory.removeFirst()
        }
    }

    fun cancelEcho(captureFrame: ShortArray): ShortArray {
        if (captureFrame.isEmpty() || reverseHistory.isEmpty()) {
            return captureFrame
        }

        var bestReference: ShortArray? = null
        var bestCorrelation = 0f
        var bestGain = 0f

        for (candidate in reverseHistory) {
            val (correlation, gain) = estimateCorrelationAndGain(captureFrame, candidate)
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestGain = gain
                bestReference = candidate
            }
        }

        if (bestReference == null || bestCorrelation < correlationGate) {
            return captureFrame
        }

        val output = ShortArray(captureFrame.size)
        val limit = minOf(captureFrame.size, bestReference.size)
        for (i in 0 until limit) {
            val canceled = captureFrame[i] - bestReference[i] * bestGain * suppressRatio
            output[i] = canceled
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        for (i in limit until captureFrame.size) {
            output[i] = captureFrame[i]
        }
        return output
    }

    private fun estimateCorrelationAndGain(
        captureFrame: ShortArray,
        referenceFrame: ShortArray
    ): Pair<Float, Float> {
        val size = minOf(captureFrame.size, referenceFrame.size)
        if (size == 0) {
            return 0f to 0f
        }

        var dot = 0.0
        var captureEnergy = 0.0
        var referenceEnergy = 0.0
        for (i in 0 until size) {
            val mic = captureFrame[i].toDouble()
            val ref = referenceFrame[i].toDouble()
            dot += mic * ref
            captureEnergy += mic * mic
            referenceEnergy += ref * ref
        }

        if (captureEnergy < 1.0 || referenceEnergy < 1.0) {
            return 0f to 0f
        }

        val correlation = abs(dot / sqrt(captureEnergy * referenceEnergy)).toFloat()
        val gain = (dot / referenceEnergy)
            .toFloat()
            .coerceIn(0f, 1.2f)
        return correlation to gain
    }
}
