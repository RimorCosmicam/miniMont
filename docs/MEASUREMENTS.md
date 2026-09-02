# What we do not know, and how to find out

Written before any code, so that the guesses in the other documents are labelled as
guesses and each has an experiment attached. AirMate's `docs/VIRTUAL_DISPLAY.md` is
the model: try it, write down what actually happened, delete the shim that looked
like a working mechanism and was not.

Ordered by how much depends on the answer.

## 1. What a display without system decorations can still do

**Why it matters:** it is the whole product. miniMont exists by turning off
`VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS` so that One UI puts nothing on
the display and miniMont can put everything there instead. If activities will not
launch on such a display, or will not go freeform on it, there is no miniMont in
this shape.

**The experiment:** take AirMate's `androidhost` as it stands, drop bit 9 from the
flag word — `flags=` is already a command-line override on its server, so this needs
no rebuild — and then, on the resulting display:

- `am start --display <id>` an ordinary app. Does it land?
- `wm set-display-windowing-mode -d <id> 5`, then launch. Is it a window?
- Can the app put its own window on that display through a display context?
- What is *not* there: wallpaper, navigation bar, home, IME. Write down which.

**Record:** which flag combinations produce a usable empty display, in the same
table-of-attempts form as AirMate's virtual display notes.

## 2. The system's own caption bar

**Why it matters:** if the framework draws a freeform caption above each window,
miniMont's title bar is the second one on the screen, and [SHELL.md](SHELL.md)'s
chrome is fiction.

**The experiment:** with a window up on the display from (1), look. If there is a
caption, find out whether it goes away with decorations off, or with a Samsung flag,
or not at all. `dumpflags` on AirMate's server already dumps every
`VIRTUAL_DISPLAY_FLAG` the device defines — the answer may be one bit.

**If it cannot be removed:** the design changes rather than the code fighting it.
Say so early.

## 3. A pointer that other apps obey

**Why it matters:** it is the second gate on the product, and unlike most of this
list it is not a guess — it is a known failure. In miniDex the cursor moves over DeX
and cannot open anything in it, because a UHID mouse belongs to the system rather
than to a display, and what is left on screen is a drawn cursor plus synthesised taps
that a launcher icon does not answer. miniMont cannot ship that. See
[INPUT.md](INPUT.md).

**The experiments, in order, stopping at the first yes:**

1. **`VirtualDeviceManager`.** Can the shell uid create a virtual device and a
   `VirtualMouse` configured against our display? Does `CREATE_VIRTUAL_DEVICE` come
   with uid 2000, or can it be granted? Does the display have to be created through
   the virtual device — and if so, does an app launched on *that* display still
   behave, or does virtual-device policy start refusing apps?
2. **Injected mouse events.** `MotionEvent` with `SOURCE_MOUSE`, `TOOL_TYPE_MOUSE`,
   a hover stream while no button is down, proper `buttonState` and action button on
   press, display id set on every event. Does a launcher icon open? Does a window
   take focus? Does the system draw its own pointer in response to the hover? Measure
   the injection rate a 60Hz drag needs and confirm it costs nothing like a spawn.
3. **Device association.** Bind miniDex's existing UHID mouse to the display through
   input-device-to-display association, and find out whether that is reachable from
   the shell uid on One UI 8.

**The acceptance test is not "the cursor appears".** It is: open an app from the
dock, focus a window behind another, drag a title bar, pull a window edge, rest on a
caption until the quarter chooser appears, and right-click inside a third-party app.
All six, on somebody else's windows, or the mechanism has not passed.

**Then repeat for a real Bluetooth mouse**, which asks the same question of the same
routing and probably has the same answer.

## 3b. Where the dock actually lands

**Why it matters:** the dock is a `Presentation` on the miniMont display, and a
presentation is a system-layer window, so it should float above the freeform app
windows the way a dock has to. Should. If it lands underneath them, the dock
disappears the moment anything is opened.

**The experiment:** open two apps, move one over the bottom of the screen, and look.
The window wraps its content rather than covering the display, so also check the
other half of the bargain — that a touch outside the dock band reaches the app behind
it rather than being eaten by an invisible sheet.

**If it lands underneath:** the dock moves into the backdrop activity and stops being
a dock in the macOS sense, or the chrome needs a window type the app cannot ask for
and the whole thing moves to the shell side.

## 4. Moving and sizing another app's task, at drag speed

**Why it matters:** dragging a window is the desktop. A process spawn per motion
event is not a slow drag, it is not a drag.

**The experiment:** miniDex already reaches `IActivityTaskManager` by reflection for
`move-root-task`. Find the resize and reposition equivalents on Android 16 / One UI
8, call them in a loop at 60Hz against a real task, and measure. Compare with the
shell-command versions to know what is being bought.

**Fallback if the framework call is not reachable:** a drag becomes a live outline
and the task is placed once on release. Not as good, entirely usable, and it should
be built that way first regardless — an outline that follows the finger perfectly is
better than a window that follows it badly.

## 5. USB-C: composite, or take the display

**Why it matters:** it decides whether miniMont ever touches the user's phone in a
way that outlives the process.

**The experiment, in order:**

1. Connect a monitor. DeX starts. What display id is it, and what is on it?
2. Put a full-screen `Presentation` on that display showing miniMont's own virtual
   display surface. Does it stay above DeX's launcher, its taskbar, and an app
   window launched on the DeX display? Watch it for longer than a minute — the
   question is not whether it appears, it is whether it stays.
3. Only if (2) fails: disable Samsung's DeX packages at uid 2000, see what the
   external display becomes, and — more importantly — see how completely re-enabling
   them restores the phone.

**Rule for (3):** it never happens silently, it never happens by default, it names
the packages, and the undo is in the same place as the switch. A crash must not
leave somebody with no DeX and no explanation.

## 6. Whether the door stays open

**Why it matters:** `ADD_TRUSTED_DISPLAY` is shell-uid-only, was removed in Android
15 QPR2, and was restored in 16 with a note saying it may go again. AirMate's
`DexDisplay` already carries that warning in a comment.

**Not an experiment, a standing watch:** every One UI update, check that a trusted
display can still be created, and keep the failure legible — the app should be able
to say *this Android release closed the door* rather than showing a black screen.

## 7. The IME on a display with no decorations

**Why it matters:** miniMont brings its own keyboard on the cover screen, so an app
that wants text may be fed by miniDex's IME rather than by a keyboard drawn on the
desktop. If IMEs cannot attach to a decorationless display at all, that path has to
be the key-event path instead, and that is a different quality of typing.

**The experiment:** put a text field on the display from (1) and try to type into it,
both through the IME and through injected key events with `setDisplayId`.

## 8. Density, honestly

**Why it matters:** the type scale is in dp and was drawn for a phone. 160dpi is
AirMate's argument-parser default, not a decision anybody made.

**The experiment:** put the dock, the status card and a settings card on a real
monitor at 1920×1080 across several densities and look at them from a normal
distance. Mont's figures were arrived at by looking; so should this one be.
