# Branding

The project's visual identity: the source art, and the generated icons.

## The mark

**A blue whale holding a glowing phone, two signal arcs above its head, on a dark
blue plate.** It says what the app is — an assistant you carry, reached over your
own network — without the meme caption the earlier artwork carried, which at 48px
is a smear rather than a joke.

| File | What it is |
|---|---|
| `mark-whale-1024.png` | 1024×1024. The master artwork, four corners filled in. Every icon derives from it. |
| `ic_launcher_background.png` | 432×432 (108dp at xxxhdpi). The plate layer. |
| `ic_launcher_foreground.png` | 432×432, transparent. The whale, already inside the adaptive safe zone. |
| `ic_launcher_monochrome.png` | 432×432, transparent, white. The silhouette Android 13+ tints for themed icons. |
| `play-store-icon-512.png` | 512×512, for a store listing. Not used by the build. |
| `mark-taiji.png` | 629×696. The **previous** mark — two whales in a taiji, cropped from a screenshot. Kept because it is what the first public builds shipped, and because `make-icons.ps1 -AutoCrop` is documented against it. |

The icons the build consumes live where Android requires them:
`app/src/main/res/mipmap-<density>/ic_launcher_{background,foreground,monochrome}.png`,
referenced by `mipmap-anydpi/ic_launcher.xml` and `mipmap-anydpi/ic_launcher_round.xml`.

## Installing the icons

Two paths, because artwork arrives in two shapes.

**A layer set** — a plate, a foreground and a silhouette, each already composed on
the 108dp canvas. Nothing is re-composed here, so the layers are scaled and copied
as they are:

```powershell
./scripts/install-icon-layers.ps1 -Background branding/ic_launcher_background.png `
    -Foreground branding/ic_launcher_foreground.png `
    -Monochrome branding/ic_launcher_monochrome.png `
    -Res app/src/main/res
```

**One mark image** — `make-icons.ps1` composes the icon itself: it paints the art
on a plate, keeps the mark inside the safe zone, and can derive the themed-icon
silhouette from the art's own alpha.

```powershell
./scripts/make-icons.ps1 -Source branding/mark-whale-1024.png -Res app/src/main/res -MonochromeFromArt
```

Both are Windows-only and say why in their own headers — they need an image decoder
and this project has no Node dependency. Neither has to run to build the app, only
to change the artwork, which is why the output is committed.

## Cropping a mark out of a screenshot

The artwork this project ran on for its first releases arrived as screenshots, so
the crop is part of the build rather than a manual step:

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
mostly-white rows is the background" sounds equivalent and is not: **a row crossing
the mark is not mostly white**, so that approach finds the empty margin *above* the
mark and happily reports a bounding box containing nothing. The letterbox has to be
found by looking for black.

Measured for `mark-taiji.png`: content band `y = 325..1284`, mark
`x = 54..670, y = 491..1174`, crop 629×696.

## Design notes

**The plate is a bitmap, not a colour.** The background is a gradient with a glass
disc on it, so it is a layer rather than a `@color` resource; the foreground keeps
its own transparency on top of it, which is what stops the two from disagreeing
about where the mark sits.

**Safe zone.** The foreground is composed on the 108dp adaptive canvas with the
whale inside the middle 66dp. That is the region every launcher mask is guaranteed
to show, so nothing important lives outside it — which also means the colour layer
can be re-masked by the launcher without redrawing anything.

**Themed icons are real artwork, not a filter.** `ic_launcher_monochrome.png` is a
silhouette drawn for the purpose (the whale and the signal arcs, white on
transparent). It has to be drawn rather than thresholded because the coloured mark
contains white markings: a threshold keeps those as holes and the result reads as
damage. `install-icon-layers.ps1` installs the drawn one;
`make-icons.ps1 -MonochromeFromArt` derives one only when the art already is a
solid shape on transparency, and refuses when it is not.

## Provenance and licensing — read this before publishing

The whale is **DeepSeek's brand character**; this project's composition — the phone,
the signal arcs, the plate — is its own. So the position is the ordinary one for a
third-party client, and it is written down in three places a user can actually see:
the README states it above the fold and in its own section, every page of the docs
site carries it in the footer, and the app says it at the bottom of the setup screen
and in its settings footer.

Two things were done about it beyond the disclaimer:

- **The display name is no longer DeepSeek's.** It is **"DSH BYOK"**; the full
  "DeepSeek Harness" appears only in descriptive sentences ("an independent client
  that works with DeepSeek Harness"). That is the form DeepSeek's own brand
  guidelines ask third-party projects to use — they suggest the abbreviation `DSH`
  as a project name and single out using the full mark as one.
- **The artwork is this project's own composition** of that character rather than a
  copy of DeepSeek's icon.

A disclaimer establishes good faith, which materially affects how a complaint is
handled. It is not a licence, and it does not make the trademark question go away.
`docs/ARCHITECTURE.md` records this alongside the project's other known gaps,
because it is a decision for the project owner rather than an implementation detail.

If the artwork has to change anyway, the three options are, in rough order of cost:

1. **Publish with the disclaimer** — what the project does now.
2. **Ship a mark with no third-party character in it at all.** This is the only
   option with no ambiguity, and `scripts/make-icons.ps1` takes any source image.
3. **Publish the code, not the brand** — keep the repository public and distribute a
   build with neutral artwork, with the branded build staying personal.

Anyone redistributing this project — a fork, a rebuild, a store listing — inherits
the same position. The disclaimer travels with the code, and a fork that strips it
out is a fork that has taken on the problem itself.

## The other mark

The maintainer's personal build of the same tool (`dsh-remote-app`) keeps its own
artwork at `dsh-remote-app/res/icon-source.png`. It currently uses the same whale, so
the two apps are told apart on a launcher by their labels — "DSH BYOK" for this one.
If that stops being enough, `mark-taiji.png` is the obvious second identity: it is
distinct at a glance, and it is already in this repository's history.

Two apps with the same name and the same icon are two apps nobody can tell apart on
a launcher, which is why this section exists at all.
