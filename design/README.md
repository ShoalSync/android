# Shoal design tokens

Shoal's colours, type, spacing, sizes, corner radii and icons, in one place for every app
and the website.
A token is named for what it is for (`accent`, `fg-muted`, `danger-soft`), never for its hue,
and each platform gets the same names in its own spelling.

This directory stands alone: nothing in it reads from the rest of the repository, so it can
move to a repository of its own (see [Moving out](#moving-out)).

## Layout

| Path | What |
| --- | --- |
| `tokens/color-light.json`, `tokens/color-dark.json` | The colours of each theme, with the same names in both |
| `tokens/brand.json` | Colours that are the same in both themes, such as the app icon's |
| `tokens/font.json` | The font families, Inter and the platform's monospace, and the weights used |
| `tokens/text.json` | Text styles: family, weight, size, line height and letter spacing |
| `tokens/space.json` | Space between and around things, on a 4px grid |
| `tokens/size.json` | Sizes of icons, icon tiles and the widest a page's content gets |
| `tokens/radius.json` | Corner radii |
| `fonts/` | Inter, the variable font, with its licence ([fonts/README.md](fonts/README.md)) |
| `icons.json` | The app's icons: each name and the [Lucide](https://lucide.dev) icon drawn for it |
| `icons/` | Icons of Shoal's own, such as the wave: filled, drawn with `<path>` only |
| `icons.mjs` | Reads the icons as path data, for `build.mjs` |
| `build.mjs` | Reads the tokens with [Style Dictionary](https://styledictionary.com) and writes `dist/` |
| `dist/` | The built files, committed so the apps and the site build without Node |

Sizes are in px: 1px is 1dp (or sp, for text) on Android and 1pt on Apple platforms. A text
style's line height and letter spacing are px too, not the format's ratio, as every platform
sets them in points. The token files are in the [Design Tokens Community Group format](https://www.designtokens.org/tr/drafts/format/).

## Building

Change a token, then:

```sh
npm ci
npm run build
```

and commit the token and `dist/` together. To add an icon, find it on [lucide.dev](https://lucide.dev),
add its name to `icons.json` and build; `ShoalIcons` gains it. Lucide is pinned in
`package.json`, so an icon only changes when the pin does. CI builds `dist/` again and fails if it differs.

## Names on each platform

A group's `base` is the plain name, and `on` comes first:

| Token | Kotlin (`ShoalColors`) | CSS |
| --- | --- | --- |
| `color.accent.base` | `accent` | `--tf-color-accent` |
| `color.accent.on` | `onAccent` | `--tf-color-on-accent` |
| `color.accent.on-soft` | `onAccentSoft` | `--tf-color-on-accent-soft` |
| `color.surface.low` | `surfaceLow` | `--tf-color-surface-low` |
| `radius.lg` | `ShoalRadius.Lg` | `--tf-radius-lg` |
| `size.content-max` | `ShoalSize.ContentMax` | `--tf-size-content-max` |
| `space.md` | `ShoalSpace.Md` | `--tf-space-md` |
| `text.body-small` | `ShoalText.BodySmall` | `--tf-text-body-small` (the `font` shorthand) |

## Consumers

- **Android** (`../app`): Gradle adds `dist/android/kotlin` and `dist/android/res` to the app's
  sources. `ui/Theme.kt` gives the colours, text styles and radii to Material's roles, and
  sets Inter's optical size from each style's size; screens use `ShoalSpace` and
  `ShoalSize` directly, and `ShoalIcons` (`dist/android/kotlin/.../Icons.kt`) for
  icons. The resources colour the window and the launcher icon, and carry the font.
- **Website** (`shoal/site`): `static/tokens.css` is a copy of `dist/css/tokens.css`. After
  changing a colour, copy it there in a pull request of its own.
- **Apple apps** (planned): a Swift output joins `dist/` when the iOS app starts, and
  `dist/icons/*.svg` go into its asset catalog. Lucide's licence (ISC) is in
  `dist/icons/LICENSE-lucide.txt`.

## Moving out

When the iOS app starts, this directory becomes its own repository, history included:

```sh
git subtree split --prefix=design -b design-only
```

Push `design-only` to the new repository. There, a release attaches `dist/` as an archive,
and the Android app and the site each pin a release, as they pin the monorepo's today.
