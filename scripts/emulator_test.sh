#!/usr/bin/env bash
set -euo pipefail
sdk_root="${ANDROID_HOME:?Android SDK path must be provided by the runner}"
export PATH="$sdk_root/cmdline-tools/latest/bin:$sdk_root/platform-tools:$sdk_root/emulator:$PATH"
sdkmanager --install 'platform-tools' 'emulator' 'system-images;android-29;google_apis;x86_64'
echo no | avdmanager create avd --name apm-ci --package 'system-images;android-29;google_apis;x86_64' --force
if [[ -e /dev/kvm ]]; then sudo chmod 666 /dev/kvm; fi
"$ANDROID_HOME/emulator/emulator" -avd apm-ci -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect > "$RUNNER_TEMP/apm-emulator.log" 2>&1 &
emulator_pid=$!
finish() {
  status=$?
  if [[ $status != 0 ]]; then cat "$RUNNER_TEMP/apm-emulator.log"; fi
  kill "$emulator_pid" 2>/dev/null || true
  exit "$status"
}
trap finish EXIT
connected=false
for attempt in $(seq 1 150); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then exit 1; fi
  if [[ "$(adb get-state 2>/dev/null || true)" == device ]]; then connected=true; break; fi
  sleep 2
done
if [[ "$connected" != true ]]; then exit 1; fi
booted=false
for attempt in $(seq 1 120); do
  if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  sleep 2
done
if [[ "$booted" != true ]]; then cat "$RUNNER_TEMP/apm-emulator.log"; exit 1; fi
if [[ "${1:-}" == --boot-only ]]; then
  adb shell getprop ro.build.version.sdk
  exit 0
fi
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
./gradlew :consumer:connectedReleaseAndroidTest
