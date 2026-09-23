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

## Building

The app is built against the `tidefell-ffi` AAR attached to the monorepo release pinned in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) (`tidefell = "X.Y.Z"`, ADR 0014).

```sh
scripts/fetch-ffi.sh          # downloads app/libs/tidefell-ffi-X.Y.Z.aar with gh, checks its sha256
./gradlew :app:assembleDebug
```

The monorepo is private, so `gh` needs access to `tidefell/tidefell`. To work on the FFI itself,
build against a monorepo checkout instead of the pinned AAR (needs the NDK and `cargo-ndk`):

```sh
./gradlew :app:assembleDebug -PtidefellFfiLocalPath=../tidefell -Ptidefell.abis=x86_64
```

Requires JDK 21 and the Android SDK (compile SDK 37; minimum SDK 29).

## How it works

- **Pairing.** A new phone shows a pairing code as a QR code. A device holding your user key
  approves it with `tidefell pair <code>` after both show the same six-digit confirmation code
  (ADR 0015).
- **Folders.** After pairing, the phone lists the folders you can join and joins them as partial
  replicas within the storage budget.
- **Files app.** Each folder is a root in the system file picker. Opening a file fetches it from
  your other devices; saving one syncs it to them (ADR 0016).
- **Background sync.** A foreground service keeps the peer running.

## Tests

`scripts/ci-exit-criterion.sh` runs the Phase 4 exit criterion on an emulator: the app pairs with
the pinned release's `tidefelld` on the host through a local iroh relay, joins its folder, opens a
file on demand through the documents provider and saves one that syncs back. CI runs it on every
push.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). A signed CLA is required.

## Conventions

Read [CONVENTIONS.md](CONVENTIONS.md) before making changes.

## License

[GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`).
