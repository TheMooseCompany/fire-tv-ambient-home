#!/usr/bin/env bash
# Installs Ambient Home on an Amazon Fire TV (Fire OS) over adb, from a Mac or a Linux
# machine on the same Wi-Fi (for example the Umbrel terminal on a Raspberry Pi).
#
#   bash install.sh <fire-tv-ip> [wallpaper-folder-or-zip]
#
# Wallpapers: 1920x1080 JPEGs in a folder or zip whose name starts with "ambient-wallpapers"
# (the newest one wins). On a Mac the script looks in Downloads, Desktop and Documents;
# on Linux under /home, /mnt and /media. Or pass the folder or zip as the second argument.
# Undo everything with: bash undo.sh <fire-tv-ip>
# Written for macOS's bash 3.2 as well as Linux.
set -euo pipefail

PKG=com.themoosecompany.ambienthome
REPO=TheMooseCompany/fire-tv-ambient-home
SERVICE="$PKG/$PKG.HomeGuardService"
APPS="${APPS:-YouTube,Netflix,Prime Video,Stremio}"
ROTATE_SECONDS="${ROTATE_SECONDS:-10}"   # how long each photo stays up (minimum 5)
MOTION="${MOTION:-true}"                 # slow zoom on each photo
IDLE_SECONDS="${IDLE_SECONDS:-20}"       # hide the apps and buttons after this long without the remote (0 = never)
OS=$(uname -s)

TV="${1:-}"
if [ -z "$TV" ]; then
  echo "Usage: bash install.sh <fire-tv-ip> [wallpaper-folder-or-zip]"
  exit 1
fi
TARGET="$TV:5555"
say() { printf '\n==> %s\n' "$*"; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# --------------------------------------------------------------------- adb
say "Checking for adb"
ADB=""
if command -v adb >/dev/null 2>&1; then
  ADB=$(command -v adb)
elif [ "$OS" = Darwin ]; then
  tools="$HOME/.ambient-home/platform-tools"
  if [ ! -x "$tools/adb" ]; then
    echo "Downloading Google's Android platform tools (adb) into ~/.ambient-home"
    mkdir -p "$HOME/.ambient-home"
    curl -fsSL -o "$tmp/pt.zip" https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
    rm -rf "$tools"
    unzip -q "$tmp/pt.zip" -d "$HOME/.ambient-home"
  fi
  ADB="$tools/adb"
else
  echo "Installing adb (Umbrel removes it again on restart, which is fine)."
  sudo apt-get update -qq
  sudo apt-get install -y -qq adb
  ADB=$(command -v adb)
fi
"$ADB" version | head -n 1
A() { "$ADB" -s "$TARGET" "$@"; }

# --------------------------------------------------------------------- connect
say "Connecting to the Fire TV at $TARGET"
"$ADB" connect "$TARGET" >/dev/null 2>&1 || true
for i in $(seq 1 45); do
  [ "$("$ADB" -s "$TARGET" get-state 2>/dev/null || true)" = device ] && break
  if [ "$i" = 1 ]; then
    echo "Look at the TV: if it asks 'Allow USB debugging?', tick 'Always allow from this computer' and choose OK."
  fi
  sleep 2
  "$ADB" connect "$TARGET" >/dev/null 2>&1 || true
done
if [ "$("$ADB" -s "$TARGET" get-state 2>/dev/null || true)" != device ]; then
  echo "Couldn't connect. Check that ADB debugging is on and that $TV is the TV's IP address"
  echo "(Settings > My Fire TV > About > Network), then run this again."
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

# --------------------------------------------------------------------- app
say "Downloading the latest Ambient Home and checking it"
base="https://github.com/$REPO/releases/latest/download"
curl -fsSL -o "$tmp/ambient-home.apk" "$base/ambient-home.apk"
curl -fsSL -o "$tmp/ambient-home.apk.sha256" "$base/ambient-home.apk.sha256"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$tmp" && sha256sum -c ambient-home.apk.sha256)
else
  (cd "$tmp" && shasum -a 256 -c ambient-home.apk.sha256)
fi

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
echo "Installed."

# --------------------------------------------------------------------- wallpapers
say "Copying wallpapers and settings"
if [ "$OS" = Darwin ]; then
  SUDO=""
  search_dirs="$HOME/Downloads $HOME/Desktop $HOME/Documents"
else
  SUDO="sudo"
  search_dirs="/home /mnt /media"
fi

# One folder at a time, so a Mac only asks for access to the folders it actually searches.
# Within the first folder that has any, the most recently changed folder or zip wins.
find_wallpapers() {
  for d in $search_dirs; do
    [ -d "$d" ] || continue
    best=""
    while IFS= read -r r; do
      [ -n "$r" ] || continue
      if [ -z "$best" ] || [ "$r" -nt "$best" ]; then best="$r"; fi
    done < <($SUDO find "$d" -maxdepth 6 \( -type d -name 'ambient-wallpapers*' \) -o \( -type f -name 'ambient-wallpapers*.zip' \) 2>/dev/null || true)
    if [ -n "$best" ]; then
      echo "$best"
      return 0
    fi
  done
}
src="${2:-}"
# A browser may have unzipped the download already: use the folder it made.
if [ -n "$src" ] && [ ! -e "$src" ] && [ -d "${src%.zip}" ]; then
  src="${src%.zip}"
fi
if [ -z "$src" ]; then
  src=$(find_wallpapers)
fi
case "$src" in
  *.zip)
    echo "Unpacking $src"
    $SUDO cp "$src" "$tmp/w.zip"
    [ -n "$SUDO" ] && sudo chown "$(id -u):$(id -g)" "$tmp/w.zip"
    mkdir -p "$tmp/unzipped"
    unzip -q "$tmp/w.zip" -d "$tmp/unzipped"
    first=$(find "$tmp/unzipped" -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) | head -n 1)
    src=""
    [ -n "$first" ] && src=$(dirname "$first")
    ;;
esac

# Fire OS 8 (Android 11) closes /sdcard/Android/data/<app> to adb; the app's media folder stays open.
dest="/sdcard/Android/media/$PKG"
A shell mkdir -p "$dest/wallpapers"
if [ -n "$src" ] && $SUDO test -d "$src"; then
  mkdir -p "$tmp/w"
  $SUDO find "$src" -maxdepth 1 -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) -exec cp {} "$tmp/w/" \;
  [ -n "$SUDO" ] && sudo chown -R "$(id -u):$(id -g)" "$tmp/w"
  count=$(find "$tmp/w" -type f | wc -l | tr -d ' ')
  echo "Found $count photos in $src"
  A shell "rm -f $dest/wallpapers/*"
  A push "$tmp/w/." "$dest/wallpapers/" >/dev/null
else
  echo "No 'ambient-wallpapers' folder or zip found, so no photos copied (the background stays dark grey)."
fi
printf 'apps=%s\nrotate_seconds=%s\nshuffle=false\nmotion=%s\nidle_seconds=%s\n' \
  "$APPS" "$ROTATE_SECONDS" "$MOTION" "$IDLE_SECONDS" > "$tmp/home.properties"
A push "$tmp/home.properties" "$dest/home.properties" >/dev/null

# --------------------------------------------------------------------- home guard
say "Turning on the Home button guard"
current=$(A shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$current" in
  null|"") wanted="$SERVICE" ;;
  *"$SERVICE"*) wanted="$current" ;;
  *) wanted="$current:$SERVICE" ;;
esac
A shell settings put secure enabled_accessibility_services "$wanted"
A shell settings put secure accessibility_enabled 1

# --------------------------------------------------------------------- open it
say "Opening Ambient Home"
A logcat -c >/dev/null 2>&1 || true
A shell am start -n "$PKG/.HomeActivity" >/dev/null
sleep 4
log=$(A logcat -d -s AmbientHome:I 2>/dev/null | tr -d '\r' || true)
missing=$(printf '%s\n' "$log" | grep "app not found" | sed 's/.*app not found on this TV: //' | sort -u || true)
if [ -n "$missing" ]; then
  echo "These apps weren't found on the TV, so they're not in the row:"
  echo "$missing"
else
  echo "All apps in the row were found."
fi
seen=$(printf '%s\n' "$log" | grep "wallpapers found" | tail -n 1 | sed 's/.*wallpapers found: //' || true)
if [ -n "$seen" ]; then
  echo "The TV can see $seen photos."
fi
logos=$(printf '%s\n' "$log" | grep "logo: " | sed 's/.*logo: //' | awk '!seen[$0]++' || true)
if [ -n "$logos" ]; then
  echo "App logos (banner = the app's own TV logo; icon = its icon on a card in the app's colour):"
  printf '%s\n' "$logos" | sed 's/^/  /'
fi
if [ "$OS" = Darwin ]; then shot="$HOME/Downloads/ambient-home-screen.png"; else shot="$PWD/ambient-home-screen.png"; fi
if A exec-out screencap -p > "$shot" 2>/dev/null && [ -s "$shot" ]; then
  echo "Saved a screenshot of the TV to $shot"
else
  rm -f "$shot"
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
