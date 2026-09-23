# Tidefell for Android

Android client for [Tidefell](https://github.com/tidefell/tidefell), open-source peer-to-peer
file sync with no central server.

- **Kotlin + Jetpack Compose** user interface.
- **Full peer on the device.** The Tidefell engine runs as a foreground service through the
  `tidefell-ffi` AAR (uniffi bindings to the Rust core), so the phone is a real peer rather than a
  thin client.
- **Placeholders via `DocumentsProvider`.** Files appear in the system file picker and other apps
  without being stored locally; contents are fetched from peers on demand.
- **Camera-roll backup** to your other devices.

> **Note:** the `tidefell-ffi` AAR is not yet published, and there is no Gradle project yet.
> The app will not build until Phase 4.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). A signed CLA is required.

## Conventions

Read [CONVENTIONS.md](CONVENTIONS.md) before making changes.

## License

[GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`).
