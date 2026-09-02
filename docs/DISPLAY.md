# The display

## The whole trick, inverted

AirMate's `DexDisplay` carries a comment that is, read the right way, miniMont's
entire specification:

> Nothing here draws a desktop or imitates one. It creates an ordinary Android
> virtual display with system decorations, and One UI does the rest on its own: its
> SecondaryLauncher and DexTaskbar appear on any trusted display large enough to
> hold them.

And, on one flag in particular:

> Without this One UI puts nothing on the display at all: no launcher, no taskbar,
> no desktop. It is the flag that turns a surface into a screen.

That flag is `VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS`, bit 9.

AirMate turns it on to summon DeX. **miniMont turns it off, and is the desktop that
does not arrive.** No launcher, no taskbar, no wallpaper, nothing — and then
miniMont puts its own backdrop, its own taskbar and its own chrome on the empty
display it just made.

Every other flag stays as AirMate has it: `PUBLIC`, `PRESENTATION`,
`OWN_CONTENT_ONLY`, `SUPPORTS_TOUCH`, `ROTATES_WITH_CONTENT`,
`DESTROY_CONTENT_ON_REMOVAL`, and on API 33 and above `TRUSTED`,
`OWN_DISPLAY_GROUP`, `ALWAYS_UNLOCKED`, `TOUCH_FEEDBACK_DISABLED`, plus `OWN_FOCUS`
and `DEVICE_DISPLAY_GROUP` from 34. Read off the framework by name with a numeric
fallback, exactly as `DexDisplay.flags()` already does, because these are hidden
constants and a rename should cost one capability rather than the whole display.

`TRUSTED` is the one that cannot be given up: it is what lets another app's activity
be launched here at all, it is uid-2000-only, and Google removed it in Android 15
QPR2 and restored it in 16 with a note saying it may go again. miniMont is built on
a door somebody else is holding open.

## What turning the flag off costs

This is the central unknown of the project and it is listed first in
[MEASUREMENTS.md](MEASUREMENTS.md). System decorations is not a single feature; it
is the switch for the display's home activity, wallpaper, navigation bar, and — the
one that matters — its IME. What is not yet known, and must be measured before any
of this is built:

- whether `am start --display` still lands an activity on a display with no
  decorations, and whether freeform windowing still applies to it;
- whether the system still draws its own freeform caption bar above each window,
  which miniMont must be rid of or must live with;
- whether an IME can appear at all, and whether that matters given miniMont brings
  its own keyboard on the cover screen;
- whether One UI cares about the difference in ways AOSP does not.

If decorations turn out to be required for windows to work at all, the fallback is
the ugly one: leave the flag on, let DeX arrive, and cover it — disable or hide
SecondaryLauncher and DexTaskbar and draw over them. That is a worse product and a
much worse thing to maintain, and it is written down here so nobody rediscovers it
as a good idea.

## Freeform

`wm set-display-windowing-mode -d <id> 5`, as AirMate already issues it, with the
same caveat its comment records: the display's own `mWindowingMode` keeps reading
fullscreen afterwards, and the mode that actually changed is the one new tasks
inherit. It shows up on a launched task and nowhere else.

Freeform is what makes the difference between a desktop and a launcher. A desktop
where every app is full screen is a phone with a longer cable.

## Two ways out, and only two

### USB-C

A real monitor on the port. Android already routes it, already routes a USB or
Bluetooth mouse and keyboard, and there is no encoder and no network in the path.

The problem is that Samsung gets there first: connect a display to a Flip and DeX
starts on it. Screen mirroring is not an escape — mirroring gives a copy of a
display, not an independent one, and a desktop cannot live on a mirror.

So there are two candidate strategies and the choice between them is a measurement,
not an opinion:

**Composite.** miniMont makes its virtual display exactly as it does for the tablet,
and instead of encoding the surface, presents it on the physical display through a
full-screen `Presentation`. One display pipeline for both outputs, no encoder, no
latency worth the name. The risk is that DeX is still running underneath and its
windows may not stay underneath.

**Take the display.** Disable Samsung's DeX packages at uid 2000, leaving the
external display as a plain Android external display, and put the desktop directly
on it. Cleaner if it works. It is also a persistent change to somebody's phone that
outlives our process, so it cannot be silent: if miniMont ever does this it does it
on an explicit choice, tells the user exactly which packages and that DeX will not
start again until they say so, and offers the undo in the same place. A crash must
not leave a phone with no DeX and no explanation.

Composite is the one to try first, because it costs the user nothing.

### AirMate

The tablet path, and the one that is already built. The display's surface goes to
`MediaCodec`, out over the existing UDP transport, and lands in the AirMate client's
decoder. Pairing is the authorisation, the tablet being sent the picture is the
tablet whose input is obeyed, and anything from another address is discarded —
AirMate's rules, unchanged.

The only thing that differs from AirMate today is what the picture is *of*: DeX
before, miniMont now. The client does not have to know.

### Everything else

Smart View, Chromecast, third-party docks, DeX itself. Not supported, not detected,
not worked around. If a user would rather use Samsung's desktop over a Miracast
link, that is a good thing to be able to do and miniMont is not in the way of it.

## Geometry

AirMate's server defaults to 1920×1080 at 160dpi and takes `size=` and `dpi=` on the
command line. miniMont keeps that shape and makes two of them a real setting,
because on a desktop dpi is not a detail: it decides whether a Mont row at 15dp is a
comfortable target or a smear. The type scale was drawn for a screen three inches
across; carrying it to twenty-seven means choosing the density deliberately rather
than inheriting 160 because it was the default in an argument parser.

Resizing is destructive here in the same way it is on the Mac — see AirMate's
`docs/VIRTUAL_DISPLAY.md` for how thoroughly that was established on the other side.
Expect to rebuild the display, expect the tasks on it to be relocated, and put the
stripes over the gap.
