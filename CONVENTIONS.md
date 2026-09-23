# Conventions

Read this before changing anything in this repository.

## Owns

The Android app: Kotlin + Jetpack Compose UI, the foreground service that hosts the
peer, the `DocumentsProvider` for placeholders, camera-roll backup and the Gradle build.

## Consumes

- The `tidefell-ffi-X.Y.Z.aar` attached to monorepo releases (ADR 0014), pinned in
  `gradle/libs.versions.toml` (`tidefell = "X.Y.Z"`) and fetched by `scripts/fetch-ffi.sh`.
- The same release's `tidefelld` for the emulator exit-criterion test.
- The generated Kotlin API in package `org.tidefell.ffi`.

## Change order

Changes to shared definitions always start in the monorepo: monorepo change → release tag →
pin bump here. This repository never defines a type, message, config key or term that the
monorepo does not. See [repository map](https://github.com/tidefell/tidefell/blob/main/docs/repos.md).

Work that spans repositories is tracked by one issue in `tidefell/tidefell`, labelled
`repo:android`.

## Local rules

- **Vocabulary:** use the terms in the [glossary](https://github.com/tidefell/tidefell/blob/main/docs/glossary.md).
- **Namespace:** `org.tidefell.android` ([ADR 0002](https://github.com/tidefell/tidefell/blob/main/docs/adr/0002-namespace.md)).
- **Never hand-write** Kotlin copies of Rust types. If the FFI lacks something, change
  `crates/ffi` in the monorepo first ([ADR 0004](https://github.com/tidefell/tidefell/blob/main/docs/adr/0004-android-ffi.md)).
- For FFI development, set the `tidefellFfiLocalPath` Gradle property to a local monorepo
  checkout instead of editing the pin.
- **Commits:** Conventional Commits; see [CONTRIBUTING.md](https://github.com/tidefell/tidefell/blob/main/CONTRIBUTING.md).

## Read first

1. [Implementation plan](https://github.com/tidefell/tidefell/blob/main/docs/plan.md)
2. [Glossary](https://github.com/tidefell/tidefell/blob/main/docs/glossary.md)
3. [Architecture decision records](https://github.com/tidefell/tidefell/blob/main/docs/adr/)
