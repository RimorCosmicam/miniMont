# Input

Three sources, one destination. The destination is always the miniMont display, and
it is never the phone.

This is the requirement that turns miniMont from a viewer into a computer: while the
desktop is running on the external screen, the FlexWindow in your hand is a trackpad
and a keyboard. Phone, screen, nothing else. No mouse to carry, no keyboard to pair,
and the desktop is complete on its own.

## miniDex has already built this

Not the design — the machine. Going through `miniDex/app/src/main` with miniMont in
mind, the input layer is not a thing to write, it is a thing to re-point.

**The cover surface.** `ui/touchpad/TouchpadView.kt`,
`ui/touchpad/TouchpadKinematics.kt` (with tests), `ui/touchpad/EdgeControls.kt` and
`EdgeRefractionSurface.kt` — the pointer surface, the acceleration curve, the scroll
rail along the edge and the click corner where the thumb already is, over a halftone
field that refracts rather than being covered.

**The keyboard.** `ui/keyboard/MontKeyboard.kt` at 490 lines, plus `SymbolKeyboard`,
`NavKeyboard` and `MacropadView`, with `domain/model/ModifierState.kt` and its tests
carrying tap-to-latch and double-tap-to-lock.

**A privileged input service.** `input/adb/PrivilegedMouseService.kt` is an
`app_process` service running at the shell uid, exposing `IMouseControl` over AIDL,
creating a HID mouse through `cpp/minidex_uhid_jni.c` — UHID, `nativeCreateMouse`,
`nativeSendReport(buttons, dx, dy, wheel)` — and separately injecting `MotionEvent`
and `KeyEvent` with `setDisplayId` by reflection, with an `exclusiveTargetDisplayId`
and a 350ms sweep to stop the pointer wandering back to the FlexWindow.

That is a great deal of working machinery. It is also, at the pointer, **the thing
miniMont must not inherit** — see below.

**A backend abstraction that already expects this.** `input/InputBackend.kt` threads
`displayId` through every single method — keys, text, pointer move, buttons, scroll —
with `AdbInputBackend`, `AccessibilityInputBackend` and `FallbackInputBackend`
behind it and `InputBackendManager` choosing between them.

**An IME**, `input/ime/MiniDexInputMethodService.kt`, for typing into apps rather
than synthesising key events at them.

I wrote in an earlier draft of this document that injection would have to be rebuilt
on uinput, because AirMate's `Input` shells out to the `input` command — a process
per gesture, which its own comment calls provisional and which cannot carry a
pointer. That is still true of AirMate's version and it is still not what miniMont
uses. But the replacement is not research. It exists, it is in the family, and it is
one repository over.

## The cursor has to actually work

In miniDex the cursor does not open apps inside DeX. It moves, it is visible, and
then it arrives at something it cannot activate. What that makes miniDex is a very
good pointing tool *alongside* a touch display — you point, and then you touch — and
what it does not make it is a mouse.

The reason is structural rather than a bug to find. A UHID mouse is a device on the
system, not a device on a display: the kernel gets a HID report, the framework gives
it to whichever display owns the pointer, and that is not ours. What is left on the
DeX screen is a cursor drawn to look like a pointer — miniDex has a
`FakeCursorOverlay` for exactly this — plus synthesised taps aimed at coordinates,
and a synthesised tap is not what a launcher icon is waiting for. There is no hover
under it, no real focus, no button state, nothing for a window to react to.

**This is a gate on miniMont, not a defect to file.** A desktop whose pointer cannot
open an application is not a desktop. The whole of [SHELL.md](SHELL.md) — dragging a
title bar, pulling an edge, resting on a caption to get the quarter chooser — needs
a pointer the system itself believes in.

One thing makes this easier here than it ever was in DeX: **miniMont draws its own
chrome.** The dock, the title bars, the launcher and the settings card are miniMont's
own windows on miniMont's own display, and they receive pointer input the ordinary
way, with no injection anywhere in the path. The hard part is only the events
destined for somebody else's window — which is precisely where miniDex fails, and
precisely what has to be solved before anything else is built.

Four candidate mechanisms, best first. Each was an experiment in
[MEASUREMENTS.md](MEASUREMENTS.md), and **B is the one that came back yes** — measured
on the phone, opening miniMont's own start menu and then a control inside the Clock's
window. It is what `server/Pointer.java` does, and A and C were never needed.

**A. A virtual mouse bound to the display.** Android 14 added `VirtualDeviceManager`
with `VirtualMouse`, `VirtualKeyboard` and `VirtualTouchscreen`, and a virtual mouse
is *configured with the display it belongs to*. That is this exact problem, solved by
the platform, and it produces a real system pointer on that display with real hover
and real buttons. The costs are that it wants `CREATE_VIRTUAL_DEVICE`, which the
shell uid may or may not hold, and that the display may have to be created through
the virtual device rather than through `DisplayManager` — which would reach back into
[DISPLAY.md](DISPLAY.md) and change how the screen is made. If it works, everything
else here is a fallback.

**B. Injected mouse events, with hover.** Inject `MotionEvent`s carrying
`SOURCE_MOUSE` and `TOOL_TYPE_MOUSE` — `ACTION_HOVER_MOVE` while nothing is pressed,
`ACTION_DOWN`/`ACTION_UP` with the button state and action button set — each with the
display id on it. Done properly this is not a synthesised tap; it is a mouse, and the
system draws its own pointer for the display in response to the hover stream. This is
the route scrcpy takes for a display where a HID mouse lands in the wrong place, and
it is the most likely answer. It costs one injection call per motion event, which is
cheap — it is the *process spawn* per event in AirMate's `input`-command approach
that was never affordable, not the injection.

**C. Bind the UHID device to the display.** Keep miniDex's real HID mouse and
associate the input device with our display, so the framework routes it there
instead of to the default display. Android has input-device-to-display association
for exactly this; whether it is reachable from the shell uid on One UI 8 is the
question.

**D. What miniDex does now.** A drawn cursor and synthesised taps. Written down
here only so that nobody arrives at it again by accident. It is the failure.

A useful property of B and C: they are both improvements to code that already exists
in `PrivilegedMouseService`, so the experiment is small. A is a different shape and
should be tried first anyway, because if the platform will hand us a real mouse on a
real display we should not be hand-rolling one.

The keyboard has the same question in a quieter form. Injected `KeyEvent`s with a
display id go to the focused window on that display and are ordinarily fine; text
into an arbitrary app still wants the IME, which is measurement 7.

## What has to change

miniDex aims at a display Samsung made. miniMont aims at a display it made itself,
and knows the id of it the moment it exists.

- The target display id stops being discovered and starts being passed in, from the
  shell process that created it, at creation.
- `exclusiveTargetDisplayId` and its 350ms sweep are a workaround shaped like the
  problem above, and should disappear entirely once the pointer genuinely belongs to
  the display. If the sweep is still there at the end, the cursor question was not
  answered, it was papered over.
- The cover UI's own layout follows the desktop's state — trackpad is the default
  surface, keyboard a gesture away, and the two never share a three-inch screen
  because sharing means neither is usable.
- The touchpad gains what a window manager needs and DeX never asked it for: a drag
  that survives a title bar, an edge-of-screen push that means snap, and a way to
  reach the quarter chooser without a hover the way a mouse has one.

That last point is the only genuinely new interaction design in the whole input
layer, and it is worth stating plainly: **hover does not exist on a trackpad the way
it exists on a mouse.** The quarter chooser in [SHELL.md](SHELL.md) appears on
pointer rest over the title bar, which a trackpad can produce — a finger down that
stops moving — but it needs to be tuned as a dwell rather than inherited as a
hover, and it must not fire while somebody is simply thinking mid-drag.

## Real peripherals

A Bluetooth or USB mouse and keyboard paired to the phone. Android routes them
without help, to whichever display has focus.
`VIRTUAL_DISPLAY_FLAG_OWN_FOCUS` is in the flag word AirMate already uses, and what
it does to a physical mouse — whether it lands on our display, on the FlexWindow, or
wherever it was last — is a measurement, not a guess. The UHID cursor asks the same
question, and gets the same answer.

Peripherals are never required. That is the product.

## The tablet

When the output is AirMate, the tablet's touches come back over the wire and land on
the display as touches. AirMate's client vocabulary today is tap and scroll and
nothing else. A desktop needs a press, a move and a release so a title bar can be
dragged and a window edge pulled, and that is the one protocol addition this project
asks of AirMate.

## Latency

Nothing above matters if the pointer lags. The budget is the one miniDex already
meets on this hardware over this connection, and the USB-C path should beat it,
because there is no encoder and no network anywhere in it.

Measure it, and put the number here when there is one.
