# Architecture

Three processes on one phone, and a fourth on the other end only when the other end
is a tablet.

```
  cover display                 external screen
  ┌────────────┐                ┌──────────────────────────┐
  │ miniMont   │                │  miniMont desktop        │
  │ app        │                │  backdrop / taskbar /    │
  │ uid app    │                │  window chrome           │
  │            │                │  ┌────────┐ ┌─────────┐  │
  │ trackpad   │                │  │ any    │ │ any     │  │
  │ keyboard   │                │  │ app    │ │ app     │  │
  └─────┬──────┘                └──┴────────┴─┴─────────┴──┘
        │ local socket                     ▲
        ▼                                  │ Surface
  ┌────────────────────────────────────────┴─────────────┐
  │ miniMont shell process — uid 2000, started over ADB   │
  │ trusted virtual display · encoder · transport · input │
  └───────────────────────────────────────────────────────┘
                     │ USB-C: local composite
                     │ AirMate: UDP over Wi-Fi
                     ▼
```

## The shell process

`app_process`, launched over the ADB connection the app pairs itself. Runs as uid
2000 because of exactly one permission — `ADD_TRUSTED_DISPLAY` — and then keeps
everything downstream of the display in the same process so the hot path never
hands a `Surface` across a binder.

It owns:

- the display, and its lifetime;
- the encoder and the transport, when the output is an AirMate tablet;
- input injection, for everything the app's cover screen and the tablet produce;
- the privileged verbs the desktop needs and cannot issue itself: launching an
  activity onto a display, moving and resizing another app's task, reading what
  tasks exist.

It draws nothing. That is not a style choice, it is a boundary: a process with no
`Context` of its own and a hand-built `DisplayManager` is the wrong place to run a
UI toolkit, and AirMate's server has already found where the edges of `FakeContext`
are.

## The app

Ordinary uid, ordinary Android app. It is the cover-screen face — pairing,
connection state, settings — and it is the trackpad and the keyboard.

It is also, and this is the part that has no precedent in the family, the desktop.
The backdrop, the taskbar and the window chrome are drawn by the app onto the
display the shell process made, as ordinary windows on a secondary display. That
works because the display is trusted and public: the app can obtain a display
context for it and put windows there like anywhere else.

Which means the desktop is Compose, in Mont, on the same terms as every other
screen in the family — not a second rendering stack invented for this one job.

## What crosses between them

One local socket, the same shape AirMate's server already speaks: a control channel
of small framed messages, plus whatever the transport needs.

| Direction | What |
|---|---|
| app → shell | start, stop, geometry, output selection |
| app → shell | pointer, keys, touches from the cover display |
| app → shell | launch this package on the display; move, size, focus, close this task |
| shell → app | display id and geometry, so the app knows where to put the desktop |
| shell → app | the task list, and changes to it, so the taskbar is not a poll |
| shell → app | connection and error state, for the cover screen to report |

The last two are new. AirMate's protocol carries video and a thin control channel
because AirMate's host has nothing to say about what is on the screen — DeX owns
that. miniMont's host has to describe the desktop to the thing drawing it.

## Why the desktop is not in the shell process

It could be. scrcpy-style servers can draw. Two reasons it is not:

**The toolkit.** Compose against a `FakeContext` in a process with no package, no
resources and no lifecycle is a fight, and it is a fight that would have to be
re-fought on every One UI release. The app has a real `Context`, real resources and
the fonts already loaded.

**The lifetime.** The ADB-launched process dies when the connection does, and the
connection is the weakest link in the whole design. A desktop whose chrome vanishes
with the ADB session is worse than one that stays up and says it has lost its
privileges — because the second one can ask you to pair again.

## What is copied, and what is written

Copied from `AirMate/androidhost`, close to as-is:

`AdbMdns`, `AdbKeys`, `AdbClient`, the pairing overlay, `FakeContext`, `Ln`,
`Transport`, `Encoder`, `FrameRepeater`, `Protocol`, the `app_process` launch and
the session plumbing in `Server`.

Copied and then inverted: `DexDisplay`. Its purpose reverses — see
[DISPLAY.md](DISPLAY.md) — and the name goes with the purpose.

Dropped: AirMate's `Input`. It shells out to the `input` command and says so in its
own comment — a process per gesture, provisional, enough to keep a read-only display
from being read-only. A trackpad cannot be built on it.

Copied from **miniDex** instead, and this is the other half of the head start: the
whole cover-screen input layer, `PrivilegedMouseService` and its UHID mouse, the
per-display injection, the `InputBackend` abstraction that already carries a
`displayId` on every call, the IME, the touchpad, the keyboard pages and the
macropad. See [INPUT.md](INPUT.md).

Written from nothing: the desktop, the taskbar, the window chrome, the launcher,
the task control verbs, and the USB-C output path.
