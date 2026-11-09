# Inkpad for Android

Handwritten notes, whiteboards and PDF annotation for Android tablets, written for a pen and a
touch screen. Kotlin, a custom canvas view and Jetpack Compose.

It is the fourth edition of Inkpad, beside
[Inkpad-gnome](https://github.com/LoveProgramming-cc/Inkpad-gnome) (Python, GTK 4),
[Inkpad-iOS](https://github.com/LoveProgramming-cc/Inkpad-iOS) (Swift) and
[Inkpad-Web](https://github.com/LoveProgramming-cc/Inkpad-Web) (JavaScript). The `.inkpad` file
format and the Google Drive sync protocol are the same in all of them, byte for byte, so a notebook
written on one goes on being written on any other.

**Work in progress.** `docs/PLAN.md` is the plan (in Chinese) and says how far it has got.

## Building

Needs Android Studio (for its JDK and the Android SDK).

```sh
./gradlew :core:test            # the file format, geometry and sync, on a plain JVM
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
```

A release build is signed with the key named in `keystore.properties` at the top of the repository
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; not in git), or with the debug key when
there is none.

`./gradlew :app:assembleTrial` builds `app-trial.apk`: a release build named "Inkpad Test" that
installs beside the real app with a library of its own, for trying things on a tablet without
touching the notes there. It also writes the canvas's input log to logcat (tag `InkpadInput`), and
`adb shell am start -n io.github.inkpad.test/io.github.inkpad.MainActivity --ef selftest_zoom 0.4`
runs a scripted zoom for measuring frame times (`adb shell dumpsys gfxinfo io.github.inkpad.test`).

Install on a tablet with USB debugging on: `adb install -r app/build/outputs/apk/release/app-release.apk`.

## How it was made

Inkpad was written with the help of AI coding assistants, directed and tested by its author.

## Licence

Copyright © 2026 the Inkpad authors. Third-party parts are listed in `THIRD-PARTY-NOTICES.md`.

Inkpad is free software under the [GNU General Public License, version 3](LICENSE) or (at your
option) any later version. Parts of it are translated from Inkpad-iOS, whose licence allows the
other editions of Inkpad to use its code under the GPL.
