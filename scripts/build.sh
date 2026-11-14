#!/usr/bin/env bash
# 荣耀卡面工坊 — Linux / macOS 构建脚本
#
# 用法：
#   export JAVA_HOME=/path/to/jdk-17
#   export ANDROID_HOME=/path/to/android-sdk
#   bash scripts/build.sh                 # 默认 :app:assembleDebug
#   bash scripts/build.sh "clean :app:assembleDebug"
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TASKS="${1:-:app:assembleDebug}"

if [ -z "${JAVA_HOME:-}" ] || [ ! -d "${JAVA_HOME}" ]; then
  echo "ERROR: JAVA_HOME 未设置或不存在（需要 JDK 17）" >&2
  exit 1
fi
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "${ANDROID_HOME}" ]; then
  echo "ERROR: ANDROID_HOME 未设置或不存在（需要 Android SDK，含 Platform 37）" >&2
  exit 1
fi

if [ -x "$ROOT/gradlew" ]; then
  GRADLE="$ROOT/gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="gradle"
else
  echo "ERROR: 未找到 gradle（可用 ./gradlew 或安装 Gradle 8.13）" >&2
  exit 1
fi

cd "$ROOT"
# shellcheck disable=SC2086
"$GRADLE" $TASKS

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  echo "APK: $APK"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$APK"
  fi
fi
