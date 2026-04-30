#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {
struct DummyAecState {
    int sample_rate_hz;
    int frame_size_samples;
    std::vector<int16_t> reverse_frame;
};

float estimate_correlation(const std::vector<int16_t>& capture,
                           const std::vector<int16_t>& reference,
                           float* gain_out) {
    const size_t size = std::min(capture.size(), reference.size());
    if (size == 0) {
        *gain_out = 0.0f;
        return 0.0f;
    }

    double dot = 0.0;
    double capture_energy = 0.0;
    double reference_energy = 0.0;
    for (size_t i = 0; i < size; ++i) {
        const double mic = static_cast<double>(capture[i]);
        const double ref = static_cast<double>(reference[i]);
        dot += mic * ref;
        capture_energy += mic * mic;
        reference_energy += ref * ref;
    }

    if (capture_energy < 1.0 || reference_energy < 1.0) {
        *gain_out = 0.0f;
        return 0.0f;
    }

    const auto correlation = static_cast<float>(std::abs(dot / std::sqrt(capture_energy * reference_energy)));
    const auto gain = static_cast<float>(dot / reference_energy);
    *gain_out = std::max(0.0f, std::min(1.2f, gain));
    return correlation;
}
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_shijing_xomniclaw_accessibility_audio_WebRtcAecNativeBridge_nativeHasAec3Support(
        JNIEnv*,
        jobject) {
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_shijing_xomniclaw_accessibility_audio_WebRtcAecNativeBridge_nativeCreate(
        JNIEnv*,
        jobject,
        jint sample_rate_hz,
        jint frame_size_samples) {
    auto* state = new DummyAecState();
    state->sample_rate_hz = sample_rate_hz;
    state->frame_size_samples = frame_size_samples;
    return reinterpret_cast<jlong>(state);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_shijing_xomniclaw_accessibility_audio_WebRtcAecNativeBridge_nativePushReverseFrame(
        JNIEnv* env,
        jobject,
        jlong handle,
        jshortArray reverse_frame) {
    auto* state = reinterpret_cast<DummyAecState*>(handle);
    if (state == nullptr || reverse_frame == nullptr) {
        return;
    }
    const jsize size = env->GetArrayLength(reverse_frame);
    state->reverse_frame.resize(size);
    env->GetShortArrayRegion(reverse_frame, 0, size, state->reverse_frame.data());
}

extern "C"
JNIEXPORT jshortArray JNICALL
Java_com_shijing_xomniclaw_accessibility_audio_WebRtcAecNativeBridge_nativeProcessCaptureFrame(
        JNIEnv* env,
        jobject,
        jlong handle,
        jshortArray capture_frame) {
    auto* state = reinterpret_cast<DummyAecState*>(handle);
    if (state == nullptr || capture_frame == nullptr) {
        return capture_frame;
    }

    const jsize size = env->GetArrayLength(capture_frame);
    std::vector<int16_t> capture(static_cast<size_t>(size));
    env->GetShortArrayRegion(capture_frame, 0, size, reinterpret_cast<jshort*>(capture.data()));

    float gain = 0.0f;
    const float correlation = estimate_correlation(capture, state->reverse_frame, &gain);
    if (!state->reverse_frame.empty() && correlation > 0.18f) {
        const size_t limit = std::min(capture.size(), state->reverse_frame.size());
        constexpr float suppress_ratio = 0.78f;
        for (size_t i = 0; i < limit; ++i) {
            const float canceled = static_cast<float>(capture[i]) -
                                   static_cast<float>(state->reverse_frame[i]) * gain * suppress_ratio;
            capture[i] = static_cast<int16_t>(
                    std::max(-32768.0f, std::min(32767.0f, canceled))
            );
        }
    }

    auto output = env->NewShortArray(size);
    env->SetShortArrayRegion(output, 0, size, reinterpret_cast<const jshort*>(capture.data()));
    return output;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_shijing_xomniclaw_accessibility_audio_WebRtcAecNativeBridge_nativeRelease(
        JNIEnv*,
        jobject,
        jlong handle) {
    auto* state = reinterpret_cast<DummyAecState*>(handle);
    delete state;
}
