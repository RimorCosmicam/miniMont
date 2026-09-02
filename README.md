# miniMont

Your folded phone, being the computer.

miniMont turns a Samsung Galaxy Z Flip into a desktop on an external screen — its
own desktop, not Samsung's. Where DeX would put its launcher and its taskbar,
miniMont puts Mont: black at 92%, square corners, and one typeface doing the work
that borders and shadows used to.

Built for the Galaxy Z Flip 7.

> **Status: first version.** It builds, and it does the first four things: the picture
> goes out to an AirMate tablet, the display comes up with miniMont's own backdrop on
> it, the dock and the status card float above the windows, and the start menu opens
> a phone app as a freeform window that can be pinned and really closed.
>
> Not yet: window chrome, snapping, and a pointer of our own. The first two wait on
> whether the framework draws its own caption bars on a display with no system
> decorations; the third is [the gate](docs/INPUT.md) miniDex ran into.

## What it does

- **A desktop of its own.** miniMont creates the display and draws everything on
  it. DeX is not underneath, mirrored, or reskinned — the single flag that invites
  One UI's SecondaryLauncher and DexTaskbar onto a display is the one flag miniMont
  turns off. See [docs/DISPLAY.md](docs/DISPLAY.md).
- **Windows.** Android apps in freeform, moved and sized with a pointer, wearing
  Mont's own square chrome.
- **Or the whole screen.** A window can take the display and give it back.
- **Two ways out, and only two.** A monitor on the USB-C port, or an AirMate
  tablet over Wi-Fi. Smart View, Chromecast, whatever dock you like — that is
  Samsung's business and yours. miniMont does not compete with it and does not
  pretend to support it.
- **The phone is the input.** While the desktop runs, the cover display is a
  trackpad and a keyboard, so a phone in your hand is a whole computer. A real
  mouse and keyboard work as well, and are never required.

## What it needs

Wireless ADB, paired once, on the phone itself.

This is not a preference and there is no reduced mode hiding behind it. Only uid
2000 may create a *trusted* virtual display, and only a trusted display will host
another app's windows. An unprivileged Android app can make a display; it cannot
put anybody else's program on it. Everything miniMont is depends on that one
permission, so the pairing is the first screen and the app says so plainly.

The pairing itself is solved: mDNS finds the port, you type the six-digit code, and
the strip floats over Android's own debugging screen so the code and the field are
visible at once. That work comes across from AirMate and MiniDex intact.

## Building

```
./gradlew assembleDebug
```

One APK holds both halves: the app, and the shell-side host compiled into the same
artifact from `server/`. Nothing is pushed to `/data/local/tmp` — `app_process` is
handed this APK's own installed path as its classpath, so there is one thing to
install, one thing to update, and no writable copy of our own code sitting where
anything else on the device could read or replace it.

## Two things have to come back yes

Written at the top rather than buried, because the shape of the product depends on
both and neither is settled.

**A display with nothing on it.** miniMont exists by turning off the one flag that
invites One UI's desktop onto a display. If activities will not launch onto a
display with no system decorations, or will not go freeform there, miniMont is a
different program. [DISPLAY.md](docs/DISPLAY.md)

**A pointer other apps obey.** Where miniDex stops: its cursor moves over DeX and
cannot open anything in it, because a HID mouse belongs to the system rather than to a
display, so what lands is a drawn cursor and a synthesised tap that a launcher icon
does not answer.

Both came back **yes**, on a Z Flip 7 running Android 16. The display holds windows in
freeform with One UI putting nothing on it, and an injected mouse — `SOURCE_MOUSE`,
real button state, display id, and a hover stream while nothing is pressed — opens
miniMont's own start menu and clicks controls inside other apps' windows.
[MEASUREMENTS.md](docs/MEASUREMENTS.md) has what was run and what came back.

## How it is put together

Three parts, on one device.

- **The shell process**, at uid 2000, launched over ADB. It owns the display, the
  encoder, the transport and input injection. Copied from `AirMate/androidhost`
  and then taken somewhere else: AirMate's server exists to hand DeX a screen,
  miniMont's exists to keep DeX off one.
- **The app**, ordinary uid. The cover-screen face: pairing, connection, settings,
  and the trackpad and keyboard.
- **The desktop**, drawn by the app onto the display the shell process made. The
  backdrop, the taskbar, the window chrome, the launcher.

Read in this order:

| | |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | The three parts, and what crosses between them. |
| [docs/DISPLAY.md](docs/DISPLAY.md) | Making the screen, and taking it off DeX. Both paths out. |
| [docs/SHELL.md](docs/SHELL.md) | The desktop itself: backdrop, taskbar, windows, launching. |
| [docs/INPUT.md](docs/INPUT.md) | Cover-screen trackpad and keyboard, real peripherals, injection. |
| [docs/DESIGN.md](docs/DESIGN.md) | Mont, carried to a desktop, and what it does not yet answer. |
| [docs/MEASUREMENTS.md](docs/MEASUREMENTS.md) | Everything above that is a guess, and how to stop guessing. |

## The typeface

Mont is a commercial face from Fontfabric and the five weights are bundled in the
APK. That is fine for your own phone and is not a licence to redistribute — check
yours before publishing anything built from this tree. The design language is ours
and carries no such restriction; it will work with any geometric sans if the face
ever has to be swapped out.

## The family

miniMont stands on work that already exists and is already proven on this phone.

- **AirMate** gives it `androidhost` — the ADB pairing, the shell-uid server, the
  trusted display, `MediaCodec`, the UDP transport — and is one of its two outputs.
- **MiniDex** gives it the cover screen and rather more than that: a touchpad that
  behaves, a scroll rail and click corner under the thumb, swipe typing, a macro
  pad — and underneath them a privileged service with a real UHID mouse, per-display
  event injection, and task control by reflection. Between AirMate's display and
  miniDex's hands, most of the hard parts of miniMont are already written.
- **Mont** gives it the way it looks, and miniMont is the first thing in the
  family big enough to ask Mont questions it has never had to answer.
