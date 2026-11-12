#!/usr/bin/env bash
# Removes Ambient Home and its Home button guard; Amazon's home screen comes back.
#   bash undo.sh <fire-tv-ip>
set -euo pipefail

PKG=com.themoosecompany.ambienthome
SERVICE="$PKG/$PKG.HomeGuardService"
TV="${1:-}"
if [ -z "$TV" ]; then
  echo "Usage: bash undo.sh <fire-tv-ip>"
  exit 1
fi
TARGET="$TV:5555"
A() { adb -s "$TARGET" "$@"; }

if ! command -v adb >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq adb
fi
adb connect "$TARGET" >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  [ "$(adb -s "$TARGET" get-state 2>/dev/null || true)" = device ] && break
  sleep 2
  adb connect "$TARGET" >/dev/null 2>&1 || true
done

current=$(A shell settings get secure enabled_accessibility_services | tr -d '\r')
remaining=$(printf '%s' "$current" | tr ':' '\n' | { grep -v -x -e "$SERVICE" -e null || true; } | paste -sd: -)
if [ -z "$remaining" ]; then
  A shell settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
else
  A shell settings put secure enabled_accessibility_services "$remaining"
fi
A uninstall "$PKG" >/dev/null 2>&1 || true
A shell input keyevent KEYCODE_HOME
echo "Ambient Home is removed and Amazon's home screen is back."
echo "Turn ADB debugging off when you're done: Settings > My Fire TV > Developer Options."
