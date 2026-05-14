# FreeOllee Faces

A self-contained Android app that takes a latitude/longitude and pushes one of three
values to an Ollee Watch over Bluetooth Low Energy:

- The current temperature in °F (via Open-Meteo).
- The next sunrise or sunset time (computed locally with the NOAA solar algorithm).
- A custom 6-character string (for experimentation).

Built as a workaround for GrapheneOS users — the official Ollee app relies on Google Play
Services' Fused Location Provider, which is absent on GrapheneOS. This app uses the
platform `LocationManager` directly (so location works without Play Services) and also
accepts manual lat/lng entry.

The BLE packet format, CRC-16/CCITT-FALSE implementation, and Nordic UART service/
characteristic UUIDs were reverse-engineered by Arthur86000's
[FreeOllee](https://github.com/Arthur86000/FreeOllee). This app re-implements the
protocol in-tree rather than depending on the FreeOllee APK.

## Reference

- Design spec: [`docs/superpowers/specs/2026-05-14-freeollee-faces-app-design.md`](docs/superpowers/specs/2026-05-14-freeollee-faces-app-design.md)
- Implementation plan: [`docs/superpowers/plans/2026-05-14-freeollee-faces-app.md`](docs/superpowers/plans/2026-05-14-freeollee-faces-app.md)
- Screenshots of the official Ollee app for design reference: [`docs/reference/ollee-app-screenshots/`](docs/reference/ollee-app-screenshots/)

## Building

```
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.
