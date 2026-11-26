#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/current}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$HOME/.sdkman/candidates/gradle/current/bin:$ANDROID_HOME/platform-tools:$PATH"

gradle :app:assembleDebug --no-daemon

distrobox enter android-dev -- bash -lc '
  export ANDROID_HOME="$HOME/Android/Sdk"
  export ANDROID_AVD_HOME="$HOME/.android/avd"
  export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
  export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-xcb}"

  if ! adb devices | grep -q "emulator-"; then
    nohup emulator -avd stridecoach_phone -gpu guest -feature -Vulkan -no-snapshot >/tmp/stridecoach_emu.log 2>&1 &
  fi

  adb wait-for-device
  for _ in $(seq 1 90); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" = "1" ]; then
      break
    fi
    sleep 2
  done

  adb install -r "$PWD/app/build/outputs/apk/debug/app-debug.apk"
  adb shell pm grant com.stridecoach.running android.permission.ACCESS_FINE_LOCATION || true
  adb shell pm grant com.stridecoach.running android.permission.ACCESS_COARSE_LOCATION || true
  adb shell pm grant com.stridecoach.running android.permission.POST_NOTIFICATIONS || true
  adb shell pm grant com.stridecoach.running android.permission.ACTIVITY_RECOGNITION || true
  adb shell am start -n com.stridecoach.running/.MainActivity
'
