#!/usr/bin/env bash
# Source from a shell; all tool overrides remain local to this project.
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$PROJECT_ROOT/.tooling/jdk-25}"
export ANDROID_HOME="${ANDROID_HOME:-$PROJECT_ROOT/.tooling/android-sdk}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export GOPATH="$PROJECT_ROOT/.tooling/gopath"
export PATH="$JAVA_HOME/bin:$PROJECT_ROOT/.tooling/go/bin:$GOPATH/bin:$PROJECT_ROOT/.tooling/gradle-8.11.1/bin:$ANDROID_HOME/platform-tools:$PATH"
