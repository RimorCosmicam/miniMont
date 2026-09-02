# The desktop

Everything on the external screen, and nothing else. All of it drawn by the app, as
windows on a secondary display, in Mont.

The organising change from every other Mont interface is stated once here and argued
in [DESIGN.md](DESIGN.md): **on a desktop, a Mont surface is a card.** Not a
full-width band anchored to an edge. There is no edge close enough for that to mean
anything on a screen this size, so the chrome floats, and its size is its own.

## Backdrop

The wallpaper, or black where there is none.

Wallpaper is a setting, which means the desktop is the second surface in the family
that miniPape could be pointed at. Where there is no wallpaper the backdrop is
`#000000` opaque — not 92%, because there is nothing behind it.

The stripes appear here only as a curtain: while the display is being built, while a
resize rebuilds it, and while the tablet is disconnected. Red on black when it has
dropped, green on black as it comes back, then they part and are gone. They are
never ornament behind a working desktop.

## The dock

A Mont card, bottom of the screen, horizontally centred, floating 14dp clear of the
bottom edge. 92% black, radius 0, no border, no shadow.

Inside it, one row of application icons — pinned apps first, then anything running
that is not pinned. 44dp icons, 9dp apart, with 14dp of card around them.

The padding is symmetric here, and that is a departure worth naming: Mont's 22-left
/ 14-right asymmetry exists so that text hangs off a generous left margin. A row of
icons has no text and no left margin, so the reason for the asymmetry is absent and
the asymmetry goes with it.

**Running is simply the bright one.** An app that is running draws its icon at full
opacity; one that is only pinned draws at 58%. Same rule as a row, a chip and a
selected item everywhere else in the language, applied to the alpha of somebody
else's artwork instead of to white.

No magnification, no bounce, no reflection, no separator line, no running dot
beneath the icon. macOS earns its dock's animation with a decade of muscle memory;
Mont has none to honour and would only be imitating.

> Icons at all is the one place where a dock and Mont's *no pictographic glyphs*
> rule meet. The rule governs chrome we draw — the language refuses to say things
> with little pictures when a word will do. An application's icon is not our
> pictograph, it is that application's identity, and it is the same class of thing
> as the pixels inside its window, which we also do not redraw. If you want the dock
> to be words instead, say so and it becomes a row of names; the layout does not
> change.

## The status card

A separate Mont card to the right of the dock, with real space between the two so
that they read as two objects and not as one bar with a divider. Square: as tall as
the dock and as wide as it is tall.

Three lines, in the existing scale, using the card's own padding:

| | |
|---|---|
| Date | 11 Black, white 62%, the explanatory line |
| Time | 20 SemiBold, white 92%, letter-spacing −0.4 |
| Battery and notifications | 10 Black, battery left, count right |

Battery is a number and a percent sign, and it turns `#EF4444` under 20% — the one
accent already reserved for exactly that in Mont. The notification count is a
number, and at zero it is not drawn, because nothing announces itself.

Two cards rather than one is the whole reason this is legible: a dock is a place you
aim at and a status card is a place you read, and putting them in one rectangle
makes you aim at the thing you read.

## Windows

An app's window is the app's own rectangle. miniMont does not draw inside it and
cannot: what is in there belongs to somebody else and will be rounded, coloured and
shadowed however that somebody likes. Mont governs the chrome, the gaps and the
order, never the contents.

**The border.** Every window carries a 1px border — one physical pixel, not one dp,
so it stays a hairline on a big display — in white at 34%. Mont's border alpha, on
the rare object that needs one, and a window is that object: two apps' own artwork
can meet at an edge with no contrast between them at all, and the desktop cannot fix
that with contrast it does not control.

**The title bar.** 92% black, full window width, square, holding two things:

- a **mustard square** at the far left, `#D8A628`, the height of the cap and as wide
  as it is tall. It closes the window. It is the only filled colour in the chrome
  and the only control in the language with a shape instead of a word;
- the application's name, immediately to its right, in **Mont Black at 14**, upper
  case.

Nothing else. No minimise — a window you cannot see is in the dock, which is where
it already was. No maximise button, because filling the screen is a drag to the top
edge and a keystroke, both of which are free.

**Focus is brightness.** The focused window's name is white at 100%; every other
window's is 58%. That is the entire indicator: no shadow lifting the focused window,
no accent on its border, no dimming of anyone's contents. It is *selected is simply
the bright one*, finally applied on a screen with more than one selectable thing on
it at once.

**Moving and sizing.** Drag the title bar to move, drag an edge or a corner to size,
with a grab margin considerably wider than the 1px line it sits on.

## Snapping

Dragging a window to a screen edge fills a region. The region is shown while you
drag as the same 1px 34% border with nothing inside it, so you see the shape you are
about to get and see through it to what is under there.

| Drag to | Result |
|---|---|
| Top edge | The whole screen, less the strip the dock and the status card occupy. The dock is never covered. |
| Left edge | The left half, same bottom limit. |
| Right edge | The right half, same bottom limit. |

Quarters are not on the edges, because the corners are where sizing lives. Instead,
**resting the pointer on the title bar** brings up the quarter chooser: four
rectangles, in the same 1px border, in the shape of the screen, and clicking one
puts the window in that quarter. It appears on hover and leaves when the pointer
does.

Dragging a snapped window away from its region restores the size it had before it
was snapped. There is no tiling engine underneath this, no zones to configure and no
layout to save — five destinations and an undo.

Full screen — the app alone, no title bar, no dock, no status card — is a separate
thing from snapping to the top, and it is a windowing mode change on the task rather
than a resize to the display bounds, so apps that behave differently in the two get
what they expect.

## Settings

A Mont card, centred on the desktop. Wallpaper, display size and density, which
output, the pairing, and whatever else earns its line later.

On the cover display a Mont panel opens over the thing it edits, hard against the
top, full width, with no header — because on a three-inch screen a header costs the
first row of the list and the panel is unmistakably about the only thing behind it.
Neither of those holds here. A card in the middle of a desktop is not obviously
about anything, so this one is allowed the word `SETTINGS` at 16 SemiBold, and it is
the only header in the language.

Rows in Mont Black at 15, upper case, 9dp apart. Toggles are the 56×18 block.
Sliders are the full-width wash. The list ends with `CLOSE`.

## Launcher

Not a grid. A full-width-of-its-card list of applications by name in Mont Black at
15, filtered as you type on the keyboard already in your other hand, capped in
height and scrolling inside the cap, ending with `CLOSE`. It opens from the dock.

The dock is for the apps you chose; the launcher is for the rest of them.

## Task control

All of it privileged, all of it in the shell process, all of it exposed to the app
over the control socket as verbs rather than as shell strings:

launch a package on the display · list tasks · watch the list for changes · focus a
task · move a task · resize a task · set a task's windowing mode · close a task.

Less of this is speculative than it looks. miniDex's `PrivilegedMouseService`
already enumerates root tasks on a given display, finds the root task for a task id,
and moves a root task to a display — through `IActivityTaskManager` by reflection,
with `am display move-stack` and `cmd activity display move-root-task` behind it as
fallbacks. The pattern to copy is the one that is already there: reach for the
framework call, keep the shell command as the version that cannot break.

What is genuinely unproven is the geometry half — resizing and positioning another
app's task at a rate a dragging finger produces. `am start --display`,
`wm set-display-windowing-mode` and task resize have all moved between releases, and
the difference between a shell command and a framework call is a process spawn per
operation: survivable for a launch, fatal for a drag. Both are in
[MEASUREMENTS.md](MEASUREMENTS.md), and the honest expectation is that launching
stays a command and dragging does not.

## What the first version actually does

Written separately from the specification above, so that the two are never confused
for each other.

Built: the backdrop with its wallpaper, the dock as a floating Mont card, the four by
four mustard grid that opens the start menu, the start menu itself as a list of
installed applications, launching one into freeform on the miniMont display, the dock
showing what is open at full brightness and what is merely pinned at 58%, pinning and
unpinning, closing an app so that it is actually gone, the status card with its date,
time, battery and notification count, and the settings card with the wallpaper picker
in it.

Not built: window chrome, the mustard close square, the hairline border, snapping and
the quarter chooser. Every one of those is chrome drawn *around somebody else's
window*, and all of them wait on the same answer — whether the framework is already
drawing its own caption on these windows, and whether it can be told not to. Building
Mont title bars underneath One UI's own would be building the thing twice.

Also not built: a pointer of miniMont's own. Today the desktop is driven by the
AirMate tablet's touches, which land as real touches on a real display and work. The
cursor is [its own gate](INPUT.md) and its own piece of work.

## What is deliberately absent

No notifications on this display beyond the count on the status card. No status bar.
No system tray, no widgets, no quick settings, no desktop icons, no right-click menu
on the backdrop, no window animation beyond the curtain and the snap preview.

Each is a thing to add later if its absence is felt. When you are unsure whether to
add something, the answer is no.
