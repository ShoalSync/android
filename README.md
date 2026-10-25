# Shoal for Android

Android client for [Shoal](https://github.com/ShoalSync/shoal), open-source peer-to-peer
file sync with no central server.

- **Kotlin + Jetpack Compose** user interface.
- **Full peer on the device.** The Shoal engine runs as a foreground service through the
  `shoal-ffi` AAR (uniffi bindings to the Rust core), so the phone is a real peer rather than a
  thin client.
- **Placeholders via `DocumentsProvider`.** Files appear in the system file picker and other apps
  without being stored locally; contents are fetched from peers on demand.
- **Any folder on the phone**, the camera folder included: backed up, synced both ways, or backed
  up and then removed from the phone.

## Building

The app's `shoal-ffi` is built from the source of the monorepo release pinned in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) (`shoal = "X.Y.Z"`), so building
needs Rust, the NDK and `cargo-ndk` as well as JDK 21 and the Android SDK (compile SDK 37;
minimum SDK 30, Android 11, for All files access).

```sh
scripts/monorepo.sh           # checks out the pinned release in build/shoal
./gradlew :app:assembleDebug -Pshoal.abis=x86_64   # one ABI is quicker while developing
```

The script fetches the release from `https://github.com/ShoalSync/shoal`. To work on the FFI
itself, build against another checkout instead:

```sh
./gradlew :app:assembleDebug -PshoalFfiLocalPath=../shoal -Pshoal.abis=x86_64
```

## How it works

- **Pairing.** A new phone shows a pairing code as a QR code. A device holding your user key
  approves it with `shoal pair <code>` after both show the same six-digit confirmation code
  (ADR 0015).
- **Folders (ADR 0025).** A folder lives either in a folder on the phone, such as the camera
  folder, Documents or Download, or inside the app with files on demand. After pairing, the phone
  lists the folders you can join and asks where each should live; "Add folder" creates a new one
  from a folder on the phone. The folder picker lists the camera folder first. Folders on the phone
  need All files access, asked for the first time one is added.
- **Sync options.** Each folder on the phone has its own, in this order:
  - *Backup only* (the default): files on the phone are sent to your other devices; nothing is
    downloaded unless you ask, and nothing on the phone is changed.
  - *Two-way sync*: changes go both ways. A file deleted on the phone is removed from the phone
    only; it stays on your other devices and is not downloaded again until you open it.
  - *Remove after backup*: like backup only, and files added from then on move to the phone's
    recycle bin once a full replica, such as an always-on NAS or server, confirms it holds them
    (ADR 0022). Choosing it asks once.
- **Deleting and cleaning up.** Deleting a file on the phone never deletes it elsewhere; "Delete
  from all devices" is a separate action on each file. "Clean up" moves the files a full replica
  holds to the phone's recycle bin. Nothing the app removes is deleted until the recycle bin's
  retention runs out or you empty it, and restoring puts it back.
- **Files on your other devices.** A file of a folder on the phone that is only on your other
  devices has "Download to this phone", which puts it in the folder (ADR 0028). With two-way sync
  it then stays in sync; otherwise later changes from other devices do not replace it.
- **Files app.** Each folder inside the app is a root in the system file picker. Opening a file
  fetches it from your other devices; saving one syncs it to them (ADR 0016). Each folder on the
  phone is a read-only root too, labelled "On your other devices", listing only the files that
  are not on the phone; opening one downloads it into the folder. Files written into folders on
  the phone are announced to the media store, so galleries show them.
- **Background sync.** A foreground service keeps the peer running while the app is in use. Folders
  on the phone also sync soon after a new photo or video and every hour, by default only on
  unmetered networks.
- **Recycle bin.** Each folder's recycle bin on this phone lists what was deleted everywhere and
  what was removed from this phone only, to restore or delete for good. Settings sets how long
  this phone keeps them. Other devices keep their own copies.
- **Earlier versions and conflicts.** A file's earlier versions kept on this phone can be opened
  and restored. When a file was changed on two devices apart, a notification asks which version
  to keep; its screen opens each one and keeps one or both (ADR 0029).
- **Pause, speed and keeping files.** Syncing can be paused for a folder or the whole phone,
  upload and download speed limited, and files kept on the phone outside the space budget.
- **Crashes.** Engine logs go to logcat and to files in the app's data, and crashes of the engine
  or the app are recorded. The app says when Shoal stopped and offers to start syncing again.
  *Report* writes the crash up as an issue, with home directories taken out: it opens a new issue
  on the tracker set in Settings, filled in for the user to read and send, or shares the report
  without one (ADR 0030).
- **Status.** Each folder says what it is doing and lists files that failed to read, fetch, write
  or delete, and files say which device made them. A folder's *Activity* lists what the phone
  applied and who made each change. The *Status* page shows your devices and how they are
  connected, what has been sent and received, and *Check this phone*: the engine's checks with
  notifications, background use, battery optimisation and All files access. *Share diagnostics*
  shows a diagnostics bundle, with names hashed, before sharing it (ADR 0030).

## Tests

`./gradlew :app:testDebugUnitTest` runs the unit tests.

Every screen, in each state worth seeing (empty, paused, with problems, a dialog open), has a
screenshot test in `app/src/test/kotlin/org/shoalsync/android/ui/screenshots/`. Robolectric draws
the screens on the JVM with fixed data, through the `HomeModel`, `SettingsModel`, `StatusModel`,
`AddFolderModel` and `PairingModel` interfaces the view models implement, so no node runs.
Roborazzi compares them with the images in `app/src/test/screenshots/`:
`./gradlew :app:verifyRoborazziDebug` fails on any difference, and CI runs it; after a deliberate
change, `./gradlew :app:recordRoborazziDebug` writes the images again, and the diff of the PNGs
shows what changed. A new screen or state gets a test, and its image is looked at before it is
committed. `LargeScreenTest` draws the main screens again on a tablet, held either way, and on
the open screens of two foldables, into `app/src/test/screenshots/large/`; a page's content is
no wider than the `size.content-max` design token (`pageWidth()` in `ui/Layout.kt`).

`./gradlew :app:lintDebug` runs Android Lint and `scripts/detekt.sh` runs detekt with its formatting
rules (ADR 0031). Both fail on any finding not in their baselines, `app/lint-baseline.xml` and
`detekt-baseline.xml`; fix a new finding rather than adding it to a baseline.

`scripts/ci-exit-criterion.sh` runs the exit criteria on an emulator: the app pairs with the
pinned release's `shoald` on the host through a local iroh relay, joins one of its folders
with files on demand, opens a file through the documents provider and saves one that syncs back.
It then joins a second folder at a folder on the phone with remove after backup: a photo taken
there must reach the daemon, and move to the phone's recycle bin only after the daemon, a full
replica, confirms it, while nothing from the daemon is downloaded. Switched to two-way sync, the
daemon's files arrive, and a file deleted on the phone stays on the daemon. The phone folder's
documents root lists that file, and opening it there, or "Download to this phone", brings it back.
For ADR 0030 the phone must show the daemon connected, who made each file and what it applied;
list a file it cannot read as a problem until it can; and write a recorded crash up for a tracker
and into a diagnostics bundle without full IDs or paths. CI runs it on every push.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Conventions

Read [CONVENTIONS.md](CONVENTIONS.md) before making changes.

## License

Licensed under either of

- Apache License, Version 2.0 ([LICENSE-APACHE](LICENSE-APACHE) or
  <https://www.apache.org/licenses/LICENSE-2.0>)
- MIT license ([LICENSE-MIT](LICENSE-MIT) or <https://opensource.org/licenses/MIT>)

at your option.

All earlier versions of this code are also available under MIT OR Apache-2.0.

### Contribution

Unless you explicitly state otherwise, any contribution intentionally submitted for inclusion in
the work by you, as defined in the Apache-2.0 license, shall be dual licensed as above, without
any additional terms or conditions.
