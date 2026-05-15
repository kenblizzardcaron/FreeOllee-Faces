# Pre-built APKs

| File | Built from | SHA-256 |
|---|---|---|
| `freeollee-faces-debug-v0.2.0.apk` | v0.2 (auto-fetch previews + °F/°C toggle) on top of v0.1 (Tasks 1-12) | `2fc63a55c131d574babe8259b1ee023f2bb7ab2c09c8048caf08201d4dc60677` |

Built against Kotlin 2.2.10, AGP 9.1.1, compileSdk/targetSdk 36, minSdk 31.

Debug builds are signed with Android Studio's debug keystore — fine for side-loading onto a device you control, not appropriate for distribution. For a production-ish build, see "Building" in the repo root README.

Install via `adb`:

```
adb install -r dist/freeollee-faces-debug-v0.2.0.apk
```

Or copy the APK to the phone and tap it in the Files app.
