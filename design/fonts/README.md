# Fonts

| File | What |
| --- | --- |
| `InterVariable.ttf` | [Inter](https://rsms.me/inter/) 4.1, the variable font from `Inter-4.1.zip` of [its release](https://github.com/rsms/inter/releases/tag/v4.1), unchanged. Axes: weight (`wght`, 100 to 900) and optical size (`opsz`, 14 to 32) |
| `Inter-OFL.txt` | Its licence, the SIL Open Font License 1.1. The font's own name table carries the copyright and licence too, so a copy built into an app keeps them |

SHA-256 of `Inter-4.1.zip`: `9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e`;
of `InterVariable.ttf`: `4989b125924991b90d05b2d16e0e388c48f7d5bb8b30539bbf9c755278d0ccaf`.

`npm run build` copies the font into `dist/android/res/font/inter.ttf`. Each app sets the
optical size from the text's size, between 14 and 32, as Apple platforms do on their own.
