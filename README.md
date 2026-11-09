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
./gradlew :core:test          # the file format, geometry and sync, on a plain JVM
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

## How it was made

Inkpad was written with the help of AI coding assistants, directed and tested by its author.

## Licence

Copyright © 2026 the Inkpad authors.

Inkpad is free software under the [GNU General Public License, version 3](LICENSE) or (at your
option) any later version. Parts of it are translated from Inkpad-iOS, whose licence allows the
other editions of Inkpad to use its code under the GPL.
