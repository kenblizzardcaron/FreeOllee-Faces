# Pre-built APKs

| File | Built from | SHA-256 |
|---|---|---|
| `freeollee-faces-debug-v0.1.0.apk` | End-to-end build of Tasks 1-12 of the implementation plan | `f9a0da4b485f1cda545ae9663843f8160bf1d86136f8e3f7b40471e36ebb69a0` |

Built against Kotlin 2.2.10, AGP 9.1.1, compileSdk/targetSdk 36, minSdk 31.

Debug builds are signed with Android Studio's debug keystore — fine for side-loading onto a device you control, not appropriate for distribution. For a production-ish build, see "Building" in the repo root README.

Install via `adb`:

```
adb install -r dist/freeollee-faces-debug-v0.1.0.apk
```

Or copy the APK to the phone and tap it in the Files app.
