#!/usr/bin/env bash
# テスト用 AVD をヘッドレスで起動する。初回は AVD を作る。
# 使い方: tools/emulator.sh   (ANDROID_HOME は .android-sdk を既定にする)
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$root/.android-sdk}
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$ANDROID_HOME/avd}
image="system-images;android-36;google_apis;x86_64"

if [ ! -d "$ANDROID_AVD_HOME/test36.avd" ]; then
    mkdir -p "$ANDROID_AVD_HOME"
    echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n test36 -k "$image" -d pixel_6
fi

exec nix develop "$root#emulator" -c "$ANDROID_HOME/emulator/emulator" -avd test36 \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
