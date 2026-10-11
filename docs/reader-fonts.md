# Bundled reader fonts

Official GitHub releases checked on 2026-10-05. Fonts are bundled locally;
reading and font selection never download anything.

## Literata

- Author: TypeTogether / The Literata Project Authors.
- Latest release: [3.103](https://github.com/googlefonts/literata/releases/tag/3.103), published 2023-05-19.
- Archive: [3.103.zip](https://github.com/googlefonts/literata/releases/download/3.103/3.103.zip).
- Complete upstream variable TTF files, copied without modification:
  - `fonts/variable/Literata[opsz,wght].ttf` → `app/src/main/res/font/literata.ttf`.
  - `fonts/variable/Literata-Italic[opsz,wght].ttf` → `app/src/main/res/font/literata_italic.ttf`.
- Internal font version: 3.103. Weights: 200–900. Optical size retains the upstream default of 12.
- License: SIL OFL 1.1, included verbatim in `app/src/main/assets/licenses/literata-OFL.txt`.

The existing Literata binaries already matched the official release byte for byte.

## IBM Plex Sans

- Author: IBM.
- Latest variable release: [@ibm/plex-sans-variable@0.2.0](https://github.com/IBM/plex/releases/tag/%40ibm%2Fplex-sans-variable%400.2.0), published 2025-06-25.
- Archive: [plex-sans-variable.zip](https://github.com/IBM/plex/releases/download/%40ibm/plex-sans-variable%400.2.0/plex-sans-variable.zip).
- Complete upstream TTF files, copied without modification (not Latin-only web subsets):
  - `fonts/complete/ttf/IBM Plex Sans Var-Roman.ttf` → `app/src/main/res/font/ibm_plex_sans.ttf`.
  - `fonts/complete/ttf/IBM Plex Sans Var-Italic.ttf` → `app/src/main/res/font/ibm_plex_sans_italic.ttf`.
- Internal font version: 3.000 (distinct from the package release number). Weights: 100–700. Width retains the upstream default of 100.
- License: SIL OFL 1.1, included verbatim in `app/src/main/assets/licenses/ibm-plex-OFL.txt`.

## Reader behavior and compatibility

Literata remains the initial reading default, suitable for fiction and essays.
IBM Plex Sans replaces the system Sans choice and is available for technical reading.
Both chips preview their own fonts; regular and true italic styles are registered
at every supported hundred-weight step. Paragraphs, headings, table cells and
pagination use the same existing `fontFamilyFor` resolution.

The saved identifiers remain `LITERATA`, `SERIF`, `SANS`, and `CUSTOM`.
`SANS` now resolves to bundled IBM Plex Sans; no stored data or backup format changes.
Publisher formatting continues to resolve explicit CSS generic families and
embedded fonts as before. On leaving publisher formatting, its generic Sans
category adopts IBM Plex Sans, the reader's new Sans default.
Custom font import and its Literata fallback remain available.

All four font files were checked for Latin, Russian, Ukrainian and Belarusian
letters, including `ЁёІіЇїЄєҐґЎў`, and their OpenType tables were parsed successfully.
Device rendering and the narrow font selector still require phone verification.

## SHA-256

| Bundled file | SHA-256 |
| --- | --- |
| `literata.ttf` | `b41138c9373112f32abb589cc22e8674b06ed4048b0c513be922bdd26f274440` |
| `literata_italic.ttf` | `d483dfaeba9cbf4ce71d32a52ee65df82f7e35b15fff8d1011cdb242d1fcd465` |
| `ibm_plex_sans.ttf` | `3ed3ea3b1b3854afdf3306e9ab60773ec03cd7466bf0017131c166d943408713` |
| `ibm_plex_sans_italic.ttf` | `ad691c8be9352a5c024e3d158856b3a073841280347c25bfba2ce75cc349b7ad` |
