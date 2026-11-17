# Ambient Home for Fire TV

A calm home screen for Amazon Fire TV sticks running Fire OS: your own photos fill the screen
and slowly zoom as they rotate, with a large clock, one row of app cards, and Wi-Fi, Bluetooth
and Settings buttons. After 20 seconds without the remote the apps and buttons fade away,
leaving the photo and the clock. No ads, no recommendation rows, and no root.

- **Real app logos, nothing bundled.** Each card shows the app's own 16:9 TV banner, read from
  the app on the TV. An app without a banner gets its icon and name on a card tinted with the
  icon's colour. No brand artwork lives in this app or this repo.

- **No permissions.** The app has no internet access and no storage permission. It reads
  photos and settings from its own folder on the TV.
- **Home button guard.** Fire OS has no default-launcher setting, so a small accessibility
  service watches only Amazon's launcher and brings Ambient Home back when Amazon's home
  appears. It cannot read anything on screen. Amazon's home can flash for a moment first.
- **Nothing removed.** Amazon's launcher, settings, Bluetooth, the remote and Alexa are left
  as they are. *All apps → Amazon home* opens Amazon's home for 10 minutes when you need it.
- **Photos never go in this repo.** They are copied straight to the TV.

Not for Vega OS Fire TVs (Fire TV Stick 4K Select, 2026 Fire TV Stick HD, 3rd-gen Fire TV
Stick 4K): they can't install Android apps.

## Install (from a Linux machine on the same Wi-Fi, e.g. an Umbrel terminal)

1. On the Fire TV: **Settings → My Fire TV → About**, press OK on the device name 7 times,
   then **Developer Options → ADB debugging: On**. The IP address is under
   **About → Network**.
2. Put your wallpapers (1920×1080 JPEGs, already cropped to 16:9) in a folder or zip whose name
   starts with `ambient-wallpapers`. If there are several, the newest is used.
3. Run:

```sh
curl -fsSL -o install.sh https://raw.githubusercontent.com/TheMooseCompany/fire-tv-ambient-home/main/scripts/install.sh
bash install.sh <fire-tv-ip>
```

Press **Allow** on the TV when it asks about USB debugging. When you're done testing, turn
ADB debugging off again: network adb is unencrypted.

## Settings

`/sdcard/Android/media/com.themoosecompany.ambienthome/home.properties` on the TV (Fire OS 8 closes
`/sdcard/Android/data/<app>` to adb, so photos and settings live in the media folder):

```
apps=YouTube,Netflix,Prime Video,Stremio
# how long each photo stays up: rotate_seconds (minimum 5) or rotate_minutes
rotate_seconds=10
shuffle=false
# slow zoom on each photo
motion=true
# hide the apps and buttons after this long without the remote (0 = never)
idle_seconds=20
```

Apps are matched by the name shown on the TV. Run `install.sh` again to change them
(`APPS="..." ROTATE_SECONDS=30 MOTION=false IDLE_SECONDS=0 bash install.sh <ip>`). The script
lists which apps showed their own TV banner and saves a screenshot of the TV
(`~/Downloads/ambient-home-screen.png` on a Mac).

## Undo

```sh
curl -fsSL -o undo.sh https://raw.githubusercontent.com/TheMooseCompany/fire-tv-ambient-home/main/scripts/undo.sh
bash undo.sh <fire-tv-ip>
```

## Build

Publishing a release on GitHub (for example tag `v1.0.0`) runs GitHub Actions, which builds
`ambient-home.apk` and attaches it, with its SHA-256, to that release. Locally:
`gradle :app:assembleRelease` (JDK 17, Android SDK 34).

Signing: add the repository secrets `SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` to sign every build with the same key, so
updates install over the top. Without them each build uses a throwaway key and `install.sh`
reinstalls the app (and re-copies the photos) on update.
