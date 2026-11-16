#include <jni.h>
#include "api/audio/audio_processing.h"
#include "api/audio/builtin_audio_processing_builder.h"
#include "api/environment/environment_factory.h"

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
  auto apm = webrtc::BuiltinAudioProcessingBuilder().Build(webrtc::CreateEnvironment());
  return apm && apm->Initialize() == 0 ? JNI_VERSION_1_6 : JNI_ERR;
}
