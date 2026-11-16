#!/usr/bin/env bash
set -euo pipefail
sdkmanager --install 'platform-tools' 'emulator' 'system-images;android-29;google_apis;x86_64'
echo no | avdmanager create avd --name apm-ci --package 'system-images;android-29;google_apis;x86_64' --force
if [[ -e /dev/kvm ]]; then sudo chmod 666 /dev/kvm; fi
"$ANDROID_HOME/emulator/emulator" -avd apm-ci -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect > "$RUNNER_TEMP/apm-emulator.log" 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT
timeout 300 adb wait-for-device
booted=false
for attempt in $(seq 1 120); do
  if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  sleep 2
done
if [[ "$booted" != true ]]; then cat "$RUNNER_TEMP/apm-emulator.log"; exit 1; fi
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
./gradlew :consumer:connectedReleaseAndroidTest
