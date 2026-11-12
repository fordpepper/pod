# Pod

A tiny click-wheel music player for Android (8.0+). One canvas-drawn view, three Java files, no Gradle.

- Circle the wheel to scroll (haptic tick + click per step); tap MENU / skip / play-pause on the wheel, centre to select.
- Lists every song in the phone's media library: Music > Artists / Albums / Songs, Shuffle Songs, Now Playing.
- In Now Playing the wheel changes volume; press centre first to scrub. Hold MENU for the main menu, hold next/previous to wind.
- Settings: Shuffle, Repeat, Clicker.
- The body is drawn from real millimetre dimensions (`Hardware.java`); the screen is drawn in a 320x240 grid (`PodView.java`).
- Fonts: TeX Gyre Heros (GUST Font License, see `assets/FONT-LICENSE.txt`).

Build: `./build.sh` (needs the Android SDK at `~/android-sdk` with build-tools 34.0.0 and platform android-34, plus a debug keystore).
Install: `adb install -r pod.apk`
