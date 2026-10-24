# Conventions

Read this before changing anything in this repository.

## Owns

The Android app: Kotlin + Jetpack Compose UI, the foreground service that hosts the
peer, the `DocumentsProvider` for placeholders, camera-roll backup and the Gradle build.

For now it also holds Shoal's design tokens (`design/`), until the Apple apps start and they
move to a repository of their own.

## Consumes

- `shoal-ffi`, built from the source of the monorepo release pinned in
  `gradle/libs.versions.toml` (`shoal = "X.Y.Z"`), which `scripts/monorepo.sh` checks out.
- The same release's `shoald` for the emulator exit-criterion test, built by Nix.
- The generated Kotlin API in package `org.shoalsync.ffi`.

## Change order

Changes to shared definitions always start in the monorepo: monorepo change → release tag →
pin bump here. This repository never defines a type, message, config key or term that the
monorepo does not. See [repository map](https://github.com/ShoalSync/shoal/blob/main/docs/repos.md).

Work that spans repositories is tracked by one issue in `shoal/shoal`, labelled
`repo:android`.

## Local rules

- **Vocabulary:** use the terms in the [glossary](https://github.com/ShoalSync/shoal/blob/main/docs/glossary.md).
- **Namespace:** `org.shoalsync.android` ([ADR 0002](https://github.com/ShoalSync/shoal/blob/main/docs/adr/0002-namespace.md)).
- **Never hand-write** Kotlin copies of Rust types. If the FFI lacks something, change
  `crates/ffi` in the monorepo first ([ADR 0004](https://github.com/ShoalSync/shoal/blob/main/docs/adr/0004-android-ffi.md)).
- For FFI development, set the `shoalFfiLocalPath` Gradle property to another monorepo
  checkout instead of editing the pin.
- **Screens:** a composable takes its screen's model interface (`ui/Models.kt`), not the view
  model, so screenshot tests can draw it with fixed data. A new screen or state gets a
  screenshot test, and a new page a large-screen one too; see the README. A page's content
  uses `Modifier.pageWidth()`, so it stays readable on tablets and open foldables.
- **Look:** colours, type, spacing, sizes, corner radii and icons are design tokens in
  `design/`, shared with the website and, later, the Apple apps; change them there
  ([design/README.md](design/README.md)), never in Kotlin or resources. `ui/Theme.kt` gives the
  colours, text styles and radii to Material. A screen uses theme roles (and `statusColors` for
  fine or needs-attention states), never a colour of its own, and `ShoalSpace` and
  `ShoalSize` rather than a number of dp; a size that belongs to one screen, such as the QR
  code's, is a named value there. Icons are `ShoalIcons`, Lucide's as named in
  `design/icons.json`; controls (`Field`, `Toggle`, `ActionButton`, `Chip`, `RadioMark`,
  `IconAction`) are Shoal's own, in `ui/Controls.kt`, never Material's; a press shades what
  is pressed rather than rippling. Shared pieces such as section cards, banners and empty states
  are in `ui/Components.kt`.
- **Logging:** with `AppLog`, never `android.util.Log`. Messages go through the engine's logger,
  into logcat (tag `shoal`), the log files and diagnostics bundles. Log what runs in the
  background, what the user asked for, and every failure with its exception; never keys,
  pairing codes or file contents.
- **Commits:** Conventional Commits; see [CONTRIBUTING.md](https://github.com/ShoalSync/shoal/blob/main/CONTRIBUTING.md).

## Read first

1. [Implementation plan](https://github.com/ShoalSync/shoal/blob/main/docs/plan.md)
2. [Glossary](https://github.com/ShoalSync/shoal/blob/main/docs/glossary.md)
3. [Architecture decision records](https://github.com/ShoalSync/shoal/blob/main/docs/adr/)
