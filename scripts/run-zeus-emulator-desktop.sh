#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
exec "$ANDROID_HOME/emulator/emulator" -avd zeus-test -port 5556 -memory 2048 -cores 2 -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -no-metrics -crash-report-mode never
