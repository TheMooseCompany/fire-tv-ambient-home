#!/usr/bin/env bash
# Installs Ambient Home on an Amazon Fire TV (Fire OS) over adb, from any Linux machine on
# the same Wi-Fi. Written for the built-in Terminal on an Umbrel Raspberry Pi.
#
#   bash install.sh <fire-tv-ip> [wallpaper-folder]
#
# Wallpapers: put your 1920x1080 JPEGs in a folder named "ambient-wallpapers", or upload
# ambient-wallpapers.zip (for example with Umbrel's Files app), and the script finds it.
# Or pass the folder as the second argument.
# Undo everything with: bash undo.sh <fire-tv-ip>
set -euo pipefail

PKG=com.themoosecompany.ambienthome
REPO=TheMooseCompany/fire-tv-ambient-home
SERVICE="$PKG/$PKG.HomeGuardService"
APPS="${APPS:-YouTube,Netflix,Prime Video,Stremio}"
ROTATE_MINUTES="${ROTATE_MINUTES:-10}"

TV="${1:-}"
if [ -z "$TV" ]; then
  echo "Usage: bash install.sh <fire-tv-ip> [wallpaper-folder]"
  exit 1
fi
TARGET="$TV:5555"
say() { printf '\n==> %s\n' "$*"; }
A() { adb -s "$TARGET" "$@"; }

say "Checking for adb"
if ! command -v adb >/dev/null 2>&1; then
  echo "Installing adb (Umbrel removes it again on restart, which is fine)."
  sudo apt-get update -qq
  sudo apt-get install -y -qq adb
fi

say "Connecting to the Fire TV at $TARGET"
adb connect "$TARGET" >/dev/null 2>&1 || true
for i in $(seq 1 45); do
  [ "$(adb -s "$TARGET" get-state 2>/dev/null || true)" = device ] && break
  if [ "$i" = 1 ]; then
    echo "If the TV asks 'Allow USB debugging?', tick 'Always allow from this computer' and choose OK."
  fi
  sleep 2
  adb connect "$TARGET" >/dev/null 2>&1 || true
done
if [ "$(adb -s "$TARGET" get-state 2>/dev/null || true)" != device ]; then
  echo "Couldn't connect. Check that ADB debugging is on and that $TV is the TV's IP address."
  exit 1
fi

maker=$(A shell getprop ro.product.manufacturer | tr -d '\r')
model=$(A shell getprop ro.product.model | tr -d '\r')
sdk=$(A shell getprop ro.build.version.sdk | tr -d '\r')
echo "Found: $maker $model (Android API $sdk)"
if [ "$maker" != Amazon ]; then
  echo "This doesn't look like a Fire TV; stopping without changing anything."
  exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

say "Downloading the latest Ambient Home and checking it"
base="https://github.com/$REPO/releases/latest/download"
curl -fsSL -o "$tmp/ambient-home.apk" "$base/ambient-home.apk"
curl -fsSL -o "$tmp/ambient-home.apk.sha256" "$base/ambient-home.apk.sha256"
(cd "$tmp" && sha256sum -c ambient-home.apk.sha256)

say "Installing the app"
if ! out=$(A install -r "$tmp/ambient-home.apk" 2>&1); then
  if echo "$out" | grep -q UPDATE_INCOMPATIBLE; then
    echo "Replacing an older copy signed with a different key."
    A uninstall "$PKG" >/dev/null
    A install "$tmp/ambient-home.apk"
  else
    echo "$out"
    exit 1
  fi
fi

say "Copying wallpapers and settings"
dest="/sdcard/Android/data/$PKG/files"
A shell mkdir -p "$dest/wallpapers"
src="${2:-}"
if [ -z "$src" ]; then
  src=$(sudo find /home /mnt /media -maxdepth 8 -type d -name ambient-wallpapers 2>/dev/null | head -n 1 || true)
fi
if [ -z "$src" ]; then
  # Or a zip of them (easier to upload from a phone): ambient-wallpapers.zip
  zip=$(sudo find /home /mnt /media -maxdepth 8 -type f -name 'ambient-wallpapers*.zip' 2>/dev/null | head -n 1 || true)
  if [ -n "$zip" ]; then
    echo "Unpacking $zip"
    sudo cp "$zip" "$tmp/w.zip"
    sudo chown "$(id -u):$(id -g)" "$tmp/w.zip"
    mkdir -p "$tmp/unzipped"
    if command -v unzip >/dev/null 2>&1; then
      unzip -q "$tmp/w.zip" -d "$tmp/unzipped"
    elif command -v python3 >/dev/null 2>&1; then
      python3 -m zipfile -e "$tmp/w.zip" "$tmp/unzipped"
    else
      sudo apt-get install -y -qq unzip
      unzip -q "$tmp/w.zip" -d "$tmp/unzipped"
    fi
    src=$(find "$tmp/unzipped" -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) -printf '%h\n' | head -n 1)
  fi
fi
if [ -n "$src" ] && sudo test -d "$src"; then
  mkdir -p "$tmp/w"
  sudo find "$src" -maxdepth 1 -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) -exec cp {} "$tmp/w/" \;
  sudo chown -R "$(id -u):$(id -g)" "$tmp/w"
  count=$(find "$tmp/w" -type f | wc -l)
  echo "Found $count photos in $src"
  A shell "rm -f $dest/wallpapers/*"
  A push "$tmp/w/." "$dest/wallpapers/" >/dev/null
else
  echo "No 'ambient-wallpapers' folder found, so no photos copied (the background stays dark grey)."
fi
printf 'apps=%s\nrotate_minutes=%s\nshuffle=false\n' "$APPS" "$ROTATE_MINUTES" > "$tmp/home.properties"
A push "$tmp/home.properties" "$dest/home.properties" >/dev/null

say "Turning on the Home button guard"
current=$(A shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$current" in
  null|"") wanted="$SERVICE" ;;
  *"$SERVICE"*) wanted="$current" ;;
  *) wanted="$current:$SERVICE" ;;
esac
A shell settings put secure enabled_accessibility_services "$wanted"
A shell settings put secure accessibility_enabled 1

say "Opening Ambient Home"
A logcat -c >/dev/null 2>&1 || true
A shell am start -n "$PKG/.HomeActivity" >/dev/null
sleep 4
missing=$(A logcat -d -s AmbientHome:W 2>/dev/null | grep "app not found" | sed 's/.*app not found on this TV: //' | sort -u || true)
if [ -n "$missing" ]; then
  echo "These apps weren't found on the TV, so they're not in the row:"
  echo "$missing"
else
  echo "All apps in the row were found."
fi

say "Done"
cat <<EOF
Test with the remote:
  1. Press Home. Ambient Home should come back (Amazon's home may flash for a moment).
  2. Open each app in the row, then press Home.
  3. Try the Wi-Fi, Bluetooth and Settings buttons, then press Home.
  4. Restart the Fire TV (Settings > My Fire TV > Restart) and check it comes back here.
When you're happy, turn ADB debugging off: Settings > My Fire TV > Developer Options.
To undo everything: bash undo.sh $TV
EOF
