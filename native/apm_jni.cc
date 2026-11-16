#include <jni.h>
#include <unistd.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <memory>
#include <optional>
#include <span>
#include <vector>

#include "api/audio/audio_processing.h"
#include "api/audio/builtin_audio_processing_builder.h"
#include "api/environment/environment_factory.h"
#include "api/make_ref_counted.h"
#include "api/task_queue/task_queue_factory.h"
#include "modules/audio_processing/residual_echo_detector.h"
#include "config_generated.h"
#include "upstream_generated.h"

namespace {
using webrtc::AudioProcessing;
using webrtc::EchoCanceller3Config;
using webrtc::StreamConfig;

struct Session {
  webrtc::Environment environment = webrtc::CreateEnvironment();
  std::unique_ptr<webrtc::TaskQueueBase, webrtc::TaskQueueDeleter> dump_queue;
  webrtc::scoped_refptr<AudioProcessing> apm;
  bool linear_output_configured = false;
  std::vector<const float*> input_channels;
  std::vector<float*> output_channels;
  std::vector<std::array<float, 160>> linear;
  ~Session() {
    if (apm) apm->DetachAecDump();
    apm = nullptr;
    dump_queue.reset();
  }
};

Session& Get(jlong handle) { return *reinterpret_cast<Session*>(handle); }
void Invalid(JNIEnv* env, const char* message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
std::vector<double> Read(JNIEnv* env, jdoubleArray array) {
  if (!array) return {};
  std::vector<double> values(env->GetArrayLength(array));
  env->GetDoubleArrayRegion(array, 0, values.size(), values.data());
  return values;
}
jdoubleArray Array(JNIEnv* env, const std::vector<double>& values) {
  auto array = env->NewDoubleArray(values.size());
  if (array) env->SetDoubleArrayRegion(array, 0, values.size(), values.data());
  return array;
}

// Validate documented ranges at the JNI boundary. Preserve upstream defaults and
// pass all fields through; reject unsupported combinations rather than rewriting.
bool ValidConfig(const AudioProcessing::Config& c) {
  const auto& g1 = c.gain_controller1;
  const auto& a = g1.analog_gain_controller;
  const auto& g2 = c.gain_controller2;
  const auto& d = g2.adaptive_digital;
  return (c.pipeline.maximum_internal_processing_rate == 32000 ||
          c.pipeline.maximum_internal_processing_rate == 48000) &&
      c.pre_amplifier.fixed_gain_factor >= 0 &&
      c.capture_level_adjustment.pre_gain_factor >= 0 &&
      c.capture_level_adjustment.post_gain_factor >= 0 &&
      c.capture_level_adjustment.analog_mic_gain_emulation.initial_level >= 0 &&
      c.capture_level_adjustment.analog_mic_gain_emulation.initial_level <= 255 &&
      g1.target_level_dbfs >= 0 && g1.target_level_dbfs <= 31 &&
      g1.compression_gain_db >= 0 && g1.compression_gain_db <= 90 &&
      a.clipped_level_min >= 0 && a.clipped_level_min <= 255 &&
      a.clipped_level_step > 0 && a.clipped_level_step <= 255 &&
      a.clipped_ratio_threshold > 0 && a.clipped_ratio_threshold < 1 &&
      a.clipped_wait_frames > 0 && a.clipping_predictor.window_length > 0 &&
      a.clipping_predictor.reference_window_length > 0 &&
      a.clipping_predictor.reference_window_delay >= 0 &&
      d.headroom_db >= 0 && d.max_gain_db >= 0 && d.initial_gain_db >= 0 &&
      d.initial_gain_db <= d.max_gain_db && d.max_gain_change_db_per_second > 0 &&
      d.max_output_noise_level_dbfs <= 0 && g2.fixed_digital.gain_db >= 0 &&
      g2.fixed_digital.gain_db <= 90 &&
      !(c.pre_amplifier.enabled && c.capture_level_adjustment.enabled) &&
      !(g1.enabled && a.enabled && g2.enabled && g2.input_volume_controller.enabled);
}

jdoubleArray Defaults(JNIEnv* env, jobject, jint kind) {
  if (kind == 0) return Array(env, apm_android::Encode(AudioProcessing::Config()));
  return Array(env, apm_android::Encode(kind == 2 ?
      EchoCanceller3Config::CreateDefaultMultichannelConfig() : EchoCanceller3Config()));
}
jlong Create(JNIEnv* env, jobject, jdoubleArray config, jdoubleArray echo,
             jdoubleArray multi, jboolean residual) {
  AudioProcessing::Config c;
  if (!apm_android::Decode(Read(env, config), &c) || !ValidConfig(c)) {
    Invalid(env, "Invalid APM configuration"); return 0;
  }
  webrtc::BuiltinAudioProcessingBuilder builder(c);
  bool linear_output_configured = false;
  if (multi && !echo) {
    Invalid(env, "A multichannel AEC3 override requires a base AEC3 config"); return 0;
  }
  if (echo) {
    EchoCanceller3Config base;
    std::optional<EchoCanceller3Config> multichannel;
    if (!apm_android::Decode(Read(env, echo), &base) || !EchoCanceller3Config::Validate(&base)) {
      Invalid(env, "Invalid AEC3 configuration (upstream validation failed)"); return 0;
    }
    if (multi) {
      multichannel.emplace();
      if (!apm_android::Decode(Read(env, multi), &*multichannel) ||
          !EchoCanceller3Config::Validate(&*multichannel)) {
        Invalid(env, "Invalid multichannel AEC3 configuration"); return 0;
      }
    }
    linear_output_configured = base.filter.export_linear_aec_output &&
        (!multichannel || multichannel->filter.export_linear_aec_output);
    builder.SetEchoCancellerConfig(base, multichannel);
  }
  if (c.echo_canceller.export_linear_aec_output && !linear_output_configured) {
    Invalid(env, "Linear output requires Aec3Config.filter.exportLinearAecOutput in each supplied AEC3 config");
    return 0;
  }
  if (residual) builder.SetEchoDetector(webrtc::make_ref_counted<webrtc::ResidualEchoDetector>());
  auto session = std::make_unique<Session>();
  session->linear_output_configured = linear_output_configured;
  session->apm = builder.Build(session->environment);
  if (!session->apm) return 0;
  return reinterpret_cast<jlong>(session.release());
}
void Destroy(JNIEnv*, jobject, jlong handle) { delete &Get(handle); }
jint Initialize(JNIEnv* env, jobject, jlong handle, jintArray formats) {
  if (!formats) return Get(handle).apm->Initialize();
  if (env->GetArrayLength(formats) != 8) return AudioProcessing::kBadParameterError;
  std::array<jint, 8> v;
  env->GetIntArrayRegion(formats, 0, 8, v.data());
  webrtc::ProcessingConfig config;
  std::span streams(config.streams);
  for (int i = 0; i < 4; ++i) {
    if (v[i*2] < 8000 || v[i*2] > 384000 || v[i*2+1] <= 0)
      return AudioProcessing::kBadParameterError;
    streams[i] = StreamConfig(v[i*2], v[i*2+1]);
  }
  return Get(handle).apm->Initialize(config);
}
void ApplyConfig(JNIEnv* env, jobject, jlong handle, jdoubleArray values) {
  AudioProcessing::Config config;
  if (!apm_android::Decode(Read(env, values), &config) || !ValidConfig(config)) {
    Invalid(env, "Invalid APM configuration"); return;
  }
  if (config.echo_canceller.export_linear_aec_output && !Get(handle).linear_output_configured) {
    Invalid(env, "Recreate with AEC3 linear output configured before enabling the APM output");
    return;
  }
  Get(handle).apm->ApplyConfig(config);
}
jdoubleArray GetConfig(JNIEnv* env, jobject, jlong handle) {
  return Array(env, apm_android::Encode(Get(handle).apm->GetConfig()));
}
template <typename T>
std::span<T> Buffer(JNIEnv* env, jobject buffer, jint offset, int64_t samples) {
  if (!buffer || offset < 0 || samples <= 0) return {};
  auto capacity = env->GetDirectBufferCapacity(buffer);
  auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
  if (!base || offset > capacity || samples > (capacity - offset) / int64_t(sizeof(T)) ||
      (reinterpret_cast<uintptr_t>(base) + offset) % alignof(T) != 0) return {};
  // JNI gives a raw pointer plus capacity, not a C++ range. The checks above
  // establish this exact subrange and alignment before entering span-based code.
#pragma clang unsafe_buffer_usage begin
  return {reinterpret_cast<T*>(base + offset), static_cast<size_t>(samples)};
#pragma clang unsafe_buffer_usage end
}
jint Process(JNIEnv* env, jobject, jlong handle, jint direction, jboolean floating,
             jobject input, jint offset, jint rate, jint channels,
             jobject output, jint out_offset, jint out_rate, jint out_channels) {
  if (rate < 8000 || rate > 384000 || out_rate < 8000 || out_rate > 384000 ||
      channels <= 0 || out_channels <= 0 || direction < 0 || direction > 2)
    return AudioProcessing::kBadParameterError;
  const int width = floating ? 4 : 2;
  const int64_t input_bytes = int64_t(rate / 100) * channels * width;
  const int64_t output_bytes = int64_t(out_rate / 100) * out_channels * width;
  auto src = Buffer<uint8_t>(env, input, offset, input_bytes);
  auto dst = direction == 2 ? std::span<uint8_t>() : Buffer<uint8_t>(env, output, out_offset, output_bytes);
  if (src.empty() || (direction != 2 && dst.empty())) return AudioProcessing::kBadDataLengthError;
  if (!dst.empty()) {
    uintptr_t s = reinterpret_cast<uintptr_t>(src.data()), d = reinterpret_cast<uintptr_t>(dst.data());
    if (s < d + output_bytes && d < s + input_bytes &&
        !(s == d && rate == out_rate && channels == out_channels))
      return AudioProcessing::kBadParameterError;
  }
  auto& session = Get(handle);
  auto& apm = *session.apm;
  StreamConfig in(rate, channels), out(out_rate, out_channels);
  if (!floating) {
    if (direction == 2) return AudioProcessing::kBadParameterError;
    if (rate != out_rate || channels != out_channels) return AudioProcessing::kBadParameterError;
    auto source = Buffer<int16_t>(env, input, offset, input_bytes / 2);
    auto destination = Buffer<int16_t>(env, output, out_offset, output_bytes / 2);
    if (source.empty() || destination.empty()) return AudioProcessing::kBadDataLengthError;
    // Upstream PCM16 skips writing dest when no processing stage is active.
    // Its native callers use in-place frames; preserve that contract for our
    // distinct input/output buffers without bypassing APM analysis or state.
    if (source.data() != destination.data())
      std::copy(source.begin(), source.end(), destination.begin());
    return direction == 0 ? apm.ProcessStream(source.data(), in, out, destination.data()) :
        apm.ProcessReverseStream(source.data(), in, out, destination.data());
  }
  auto source = Buffer<float>(env, input, offset, input_bytes / 4);
  if (source.empty()) return AudioProcessing::kBadDataLengthError;
  session.input_channels.resize(channels);
  for (int i = 0; i < channels; ++i)
    session.input_channels[i] = source.subspan(i * in.num_frames(), in.num_frames()).data();
  if (direction == 2) return apm.AnalyzeReverseStream(session.input_channels.data(), in);
  auto destination = Buffer<float>(env, output, out_offset, output_bytes / 4);
  if (destination.empty()) return AudioProcessing::kBadDataLengthError;
  session.output_channels.resize(out_channels);
  for (int i = 0; i < out_channels; ++i)
    session.output_channels[i] = destination.subspan(i * out.num_frames(), out.num_frames()).data();
  return direction == 0 ? apm.ProcessStream(session.input_channels.data(), in, out, session.output_channels.data()) :
      apm.ProcessReverseStream(session.input_channels.data(), in, out, session.output_channels.data());
}
jint SetStream(JNIEnv*, jobject, jlong handle, jint setting, jint value) {
  auto& apm = *Get(handle).apm;
  switch (setting) {
    case 0: return apm.set_stream_delay_ms(value);
    case 1: apm.set_stream_analog_level(value); break;
    case 2: apm.set_stream_key_pressed(value != 0); break;
    case 3: apm.set_output_will_be_muted(value != 0); break;
    default: return AudioProcessing::kBadParameterError;
  }
  return 0;
}
jint GetStream(JNIEnv*, jobject, jlong handle, jint setting) {
  auto& apm = *Get(handle).apm;
  switch (setting) {
    case 0: return apm.stream_delay_ms();
    case 1: return apm.recommended_stream_analog_level();
    case 4: return apm.proc_sample_rate_hz();
    case 5: return apm.num_proc_channels();
    case 6: return apm.proc_split_sample_rate_hz();
    case 7: return apm.num_input_channels();
    case 8: return apm.num_output_channels();
    case 9: return apm.num_reverse_channels();
    default: return AudioProcessing::kBadParameterError;
  }
}
jboolean Runtime(JNIEnv*, jobject, jlong handle, jint type, jdouble value, jint second, jboolean queued) {
  using R = AudioProcessing::RuntimeSetting;
  R setting;
  switch (type) {
    case 1: setting = R::CreateCapturePreGain(value); break;
    case 2: setting = R::CreateCompressionGainDb(value); break;
    case 3: setting = R::CreateCaptureFixedPostGain(value); break;
    case 4: setting = R::CreatePlayoutVolumeChange(value); break;
    case 5: setting = R::CreateCustomRenderSetting(value); break;
    case 6: setting = R::CreatePlayoutAudioDeviceChange({static_cast<int>(value), second}); break;
    case 7: setting = R::CreateCapturePostGain(value); break;
    case 8: setting = R::CreateCaptureOutputUsedSetting(value != 0); break;
    default: return false;
  }
  auto& apm = *Get(handle).apm;
  if (queued) return apm.PostRuntimeSetting(setting);
  apm.SetRuntimeSetting(setting);
  return true;
}
template <typename T> double Optional(const std::optional<T>& value) {
  return value ? static_cast<double>(*value) : std::numeric_limits<double>::quiet_NaN();
}
jdoubleArray Statistics(JNIEnv* env, jobject, jlong handle, jint remote) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
  auto s = remote == -1 ? Get(handle).apm->GetStatistics() : Get(handle).apm->GetStatistics(remote != 0);
  return Array(env, {Optional(s.voice_detected), Optional(s.echo_return_loss),
      Optional(s.echo_return_loss_enhancement), Optional(s.divergent_filter_fraction),
      Optional(s.delay_median_ms), Optional(s.delay_standard_deviation_ms),
      Optional(s.residual_echo_likelihood), Optional(s.residual_echo_likelihood_recent_max), Optional(s.delay_ms)});
#pragma clang diagnostic pop
}
jboolean Linear(JNIEnv* env, jobject, jlong handle, jobject output, jint offset, jint channels) {
  auto& session = Get(handle);
  const auto config = session.apm->GetConfig();
  if (!config.echo_canceller.enabled || !config.echo_canceller.export_linear_aec_output) return false;
  if (channels <= 0 || static_cast<size_t>(channels) != session.apm->num_proc_channels()) return false;
  auto dst = Buffer<float>(env, output, offset, int64_t(channels)*160);
  if (dst.empty()) return false;
  session.linear.resize(channels);
  if (!session.apm->GetLinearAecOutput(std::span(session.linear))) return false;
  for (int c = 0; c < channels; ++c)
    std::copy(session.linear[c].begin(), session.linear[c].end(), dst.subspan(c*160, 160).begin());
  return true;
}
jboolean StartDump(JNIEnv*, jobject, jlong handle, jint fd, jlong max_bytes) {
  auto& session = Get(handle);
  int copy = dup(fd);
  if (copy < 0) return false;
  FILE* file = fdopen(copy, "wb");
  if (!file) { close(copy); return false; }
  if (!session.dump_queue) session.dump_queue = session.environment.task_queue_factory().CreateTaskQueue(
      "apm-dump", webrtc::TaskQueueFactory::Priority::kLow);
  return session.apm->CreateAndAttachAecDump(file, max_bytes, session.dump_queue.get());
}
void StopDump(JNIEnv*, jobject, jlong handle) { Get(handle).apm->DetachAecDump(); }
jstring Revision(JNIEnv* env, jobject) {
  return env->NewStringUTF(APM_UPSTREAM_REVISION);
}

#define METHOD(name, sig, function) {const_cast<char*>(name), const_cast<char*>(sig), reinterpret_cast<void*>(function)}
const JNINativeMethod methods[] = {
    METHOD("defaultConfig", "(I)[D", Defaults),
    METHOD("create", "([D[D[DZ)J", Create),
    METHOD("destroy", "(J)V", Destroy),
    METHOD("initialize", "(J[I)I", Initialize),
    METHOD("applyConfig", "(J[D)V", ApplyConfig),
    METHOD("getConfig", "(J)[D", GetConfig),
    METHOD("process", "(JIZLjava/nio/ByteBuffer;IIILjava/nio/ByteBuffer;III)I", Process),
    METHOD("setStream", "(JII)I", SetStream),
    METHOD("getStream", "(JI)I", GetStream),
    METHOD("runtime", "(JIDIZ)Z", Runtime),
    METHOD("statistics", "(JI)[D", Statistics),
    METHOD("linearOutput", "(JLjava/nio/ByteBuffer;II)Z", Linear),
    METHOD("startDump", "(JIJ)Z", StartDump),
    METHOD("stopDump", "(J)V", StopDump),
    METHOD("revision", "()Ljava/lang/String;", Revision),
};
}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass bridge = env->FindClass("org/sihai/apm/NativeBridge");
  if (!bridge || env->RegisterNatives(bridge, methods, sizeof(methods)/sizeof(methods[0])) != 0) return JNI_ERR;
  return JNI_VERSION_1_6;
}
