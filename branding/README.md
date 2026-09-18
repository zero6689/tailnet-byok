# Branding

The project's visual identity: the source art, and the generated icons.

## The mark

**Two whales in a taiji** — a blue one and a black orca, chasing each other into a
circle. It is this project's identity, and it is deliberately *different* from the
single blue whale used by the maintainer's personal build of the same tool, so the
two are distinguishable on a launcher.

| File | What it is |
|---|---|
| `mark-taiji.png` | 629×696. The mark, cropped from a phone screenshot. Source of truth for every icon. |
| `play-store-icon-512.png` | 512×512, for a Play Store listing. Not used by the build. |

The icons the build consumes live where Android requires them:
`app/src/main/res/mipmap-<density>/ic_launcher_foreground.png`, referenced by
`mipmap-anydpi/ic_launcher.xml`.

## Regenerating the icons

```powershell
./scripts/make-icons.ps1 -Source branding/mark-taiji.png -Res app/src/main/res
```

With a Play Store icon and legacy rasters:

```powershell
./scripts/make-icons.ps1 -Source branding/mark-taiji.png -Res app/src/main/res `
  -PlayStoreIcon branding/play-store-icon-512.png -Legacy
```

The script is Windows-only and says why in its own header — the short version is that
it needs an image decoder and this project has no Node dependency.

## Cropping a mark out of a screenshot

The artwork this project runs on arrives as screenshots, so the crop is part of the
build rather than a manual step:

```powershell
./scripts/make-icons.ps1 -Source shot.jpg -AutoCrop -SaveCropped branding/mark-taiji.png `
  -Res app/src/main/res
```

`-AutoCrop` does two things in a specific order, and the order is the whole point:

1. **Find the letterbox.** Take the longest run of rows that are *not* mostly black.
   That discards the viewer's bars, the status bar, the navigation bar, and — usefully
   — whatever chrome sits inside them, such as a close button or a `2/3` counter.
2. **Find the mark** inside that band: the extremes of non-white pixels, padded a few
   pixels to protect antialiased edges.

Doing it the other way round is wrong, and was wrong here first. "The longest run of
mostly-white rows is the background" sounds equivalent and is not: **a row crossing the
mark is not mostly white**, so that approach finds the empty margin *above* the mark and
happily reports a bounding box containing nothing. The letterbox has to be found by
looking for black.

Measured result for the current mark: content band `y = 325..1284`, mark
`x = 54..670, y = 491..1174`, crop 629×696.

## Design notes

**White plate.** The mark is drawn on white, and the orca's saddle patch and belly are
white too. Those areas only read against a light ground: put the mark on a dark plate
and the black whale's white markings vanish into the background. So
`ic_launcher_background` is `#FFFFFF`, and the foreground PNG is *opaque* — it carries
its own ground, so the two layers cannot disagree.

**The mark takes 72% of the canvas width.** The adaptive-icon safe zone is the central
72 of 108dp (66.7%). A roughly square mark can safely sit slightly above that: at
x = ±0.36 of the side a circular mask still has vertical room to spare. Verified by
rendering under circular, squircle and square masks before committing.

**No themed-icon (monochrome) layer.** Material You themed icons want a single-colour
silhouette. This mark is a two-tone drawing whose whole subject is the contrast between
the two whales; a threshold-based silhouette would keep the white markings as holes and
destroy exactly the thing that makes it recognisable. A correct monochrome layer means
drawing a real silhouette in the artwork. Until then, omitting the layer is better than
shipping a wrong one.

## Provenance and licensing — read this before publishing

Both whales are **DeepSeek's brand characters**, and the app's display name is
**"DeepSeek Harness"**. The taiji composition appears to be original fan artwork, and
cropping it from a screenshot does not change who owns the underlying characters.

What that means in practice:

- **For a personal build**, none of this matters. Nothing here is a problem on your own
  device.
- **For a public release**, it matters. Marking the code MIT does not license the brand:
  copyright covers the source, trademark covers the name and the marks, and they are
  separate questions. A trademark holder can ask you to stop using their mark even when
  every line of your code is your own.

Three workable options, in rough order of how much they cost you:

1. **Publish with a clear disclaimer.** — *This is what the project now does.* The README
   states it above the fold and in its own section, every page of the docs site carries it in
   the footer, and the app itself says it at the bottom of the setup screen. Wording:
   *independent, unofficial client; the name and the artwork are used descriptively; DeepSeek
   and the DeepSeek whale are trademarks of their respective owner.* This is what the
   overwhelming majority of open-source third-party clients do, and it is usually accepted.
   **It is not a legal shield** — it establishes good faith, which materially affects how a
   complaint is handled, but it does not grant permission.
2. **Ship your own mark and name.** Keep the taiji for your personal build and give the public
   project its own identity. This is the only option with no ambiguity.
3. **Publish the code, not the brand.** Keep the repository public but distribute a build that
   carries neutral artwork, with the branded build staying personal.

Anyone redistributing this project — a fork, a rebuild, a store listing — inherits the same
position. The disclaimer travels with the code, and a fork that strips it out is a fork that
has taken on the problem itself.

`docs/ARCHITECTURE.md` records this alongside the project's other known gaps, because it is a
decision for the project owner rather than an implementation detail.

## The other mark

The maintainer's personal build of the same tool uses a **single blue whale** — the
clean version of DeepSeek's mascot, without the meme caption. That art lives with that
app, at `dsh-remote-app/res/icon-source.png`, and is intentionally *not* here: the two
builds share code and nothing else, and two apps with the same name and the same icon
are two apps nobody can tell apart on a launcher.
