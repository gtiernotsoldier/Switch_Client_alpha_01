# Fonts bundled with the HUD overlay

**Do not ship this directory without reading this file.**

These two `.ttf` files are not ours: they are the typefaces embedded in the
reference OpenCommunity client, recovered from its C byte arrays by
`tools/extract_oc_fonts.py`. The reference tree carries **no font licence
file of its own** (`E:\swl\OpenCommunity` has a single project `LICENSE` and
no `OFL.txt`), so the copyright and licence strings below were read straight
out of each font's own `name` table — that is the licence the font itself
declares, and it is reproduced here verbatim rather than summarised.

## Both faces are under the SIL Open Font License, Version 1.1

* **Play** — Copyright (c) 2011, Jonas Hecksher / Playtype, with Reserved Font
  Name *Play*. Licensed under the SIL Open Font License, Version 1.1.
* **Inter** (not bundled here; see below) — Copyright (c) 2016 The Inter
  Project Authors, under the same licence.

The OFL permits redistribution and embedding, including in a binary, provided
the copyright notice and the licence travel with the font and the font is not
sold on its own. `tools/extract_oc_fonts.py` prints the exact `name`-table
strings the fonts declare; they are also recorded below.

The canonical licence text is at <https://scripts.sil.org/OFL> (OFL-1.1).
It is not reproduced in full here because this repository is not the
licensor's distribution channel and the reference checkout did not carry a
copy to include; if this overlay is ever distributed on its own, add the
verbatim `OFL.txt` from the upstream projects
(<https://github.com/google/fonts/tree/main/ofl/play>,
<https://github.com/rsms/inter>) alongside these files.

## What is bundled, and why only this

| file | source header | bytes | sha256 | used for |
| ---- | ------------- | ----- | ------ | -------- |
| `Play-Regular.ttf` | `deps/imgui/fonts/play_regular_font.h` | 171084 | `a16d8a926b82d5391cb041f928ac8a1597fc713e9bbb65e09f923b4990b58b22` | tags, values and everything else (regular) |
| `Play-Bold.ttf` | `deps/imgui/fonts/play_bold_font.h` | 170232 | `50210bae0799643632e199a2f798d5f844a6343e208c2bdd24d94526c55c111e` | module names + watermark title (bold) |

The reference also embeds face headers that this overlay deliberately does
**not** bundle: `inter_regular` / `inter_bold` (they go to
`Notifications::SetFonts`, a subsystem this HUD does not have —
`RenderHook.cpp:530-533`), plus Open Sans, SF UI Display and the
compressed variants the reference's wider UI uses. Bundling them would cost
roughly 1 MB of executable for glyphs nothing draws.

## What each font says about itself

### `Play-Regular.ttf`

Recovered from `E:\swl\OpenCommunity\deps\imgui\fonts\play_regular_font.h`.

* **copyright** — Copyright (c) 2011 by Jonas Hecksher, Playtypeª, e-types AS. All rights reserved.
* **family** — Play
* **subfamily** — Regular
* **full name** — Play
* **version** — Version 1.002
* **licence** — Copyright (c) 2011, Jonas Hecksher, Playtypes, e-types AS. All rights reserved. Available under SIL OFL v1.1.
* **licence URL** — http://scripts.sil.org/OFL

**Family / style the Rust side must use with `CreateFontW`:** `Play` / `Regular`

### `Play-Bold.ttf`

Recovered from `E:\swl\OpenCommunity\deps\imgui\fonts\play_bold_font.h`.

* **copyright** — Copyright (c) 2011 by Jonas Hecksher, Playtype¬ e-types AS. All rights reserved.
* **family** — Play
* **subfamily** — Bold
* **full name** — Play-Bold
* **version** — Version 1.002
* **licence** — Copyright (c) 2011, Jonas Hecksher, Playtypes, e-types AS. All rights reserved. Available under SIL OFL v1.1.
* **licence URL** — http://scripts.sil.org/OFL

**Family / style the Rust side must use with `CreateFontW`:** `Play` / `Bold`

## The Aurora mode's faces are Google's Roboto, Apache License 2.0

The user handed over their own HUD design (the "Spine" mockup,
`../scripts/hud_spine_mockup.html`) and its `@font-face` set is Roboto
400/500/700, so those three faces travel in the binary the same way Play does.
Roboto is **not** an OFL face: it is licensed under the **Apache License,
Version 2.0** (Copyright 2011 Google, Inc. / Luke Roberts), with the licence
and NOTICE carried in the upstream distribution
(<https://github.com/googlefonts/roboto-classic>,
<https://fonts.google.com/specimen/Roboto>). Apache-2.0 permits
redistribution and embedding in a binary; if this overlay is ever distributed
on its own, add the upstream `LICENSE.txt` beside this file.

Downloaded from Google Fonts' specimen endpoint (the static TTFs behind
`fonts.google.com`), 2026-11. The files are unmodified.

| file | bytes | sha256 | used for |
| ---- | ----- | ------ | -------- |
| `Roboto-Regular.ttf` | 123720 | `dece7c71bbe61710787f0839c722c65e87355dd2eb4a823e6b5488121ff81e4b` | Aurora: the version run and every ordinary run (400) |
| `Roboto-Medium.ttf` | 123904 | `4d984240cefb093a74300bcae4b5dbd49f700e0af501eaaa079c56aa64142d2f` | Aurora: module names and pill labels (500) |
| `Roboto-Bold.ttf` | 124292 | `35757eaa996b3359022f47f164a67ee9eac04971af24c5b8fbd4b89e844ff190` | Aurora: value chips and the pill's bold runs (700) |

**Family the Rust side must use with `CreateFontW`:** `Roboto` for the 400 and
700 roles — and **`Roboto Medium`** for the 500 role, because that file's
legacy `nameID 1` is the two-word family and GDI enumerates by it (asking
`Roboto` for weight 500 would silently hand back the 400 face). All three are
created at the design's own 11 px, not Play's 16.

