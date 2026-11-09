#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
exec "$ANDROID_HOME/emulator/emulator" -avd xbt-alpha -no-audio -no-boot-anim -gpu swiftshader_indirect
