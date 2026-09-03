package com.minimont.server;

import android.os.Looper;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * miniMont's host, running as the shell user.
 *
 * It lives here rather than in the app because of one permission: only uid 2000 may create a
 * *trusted* virtual display, and only a trusted display will host another app's windows.
 * Everything downstream of that — encoding, the wire protocol, input, launching and closing apps —
 * could have lived in the app, and is here instead so the whole hot path stays in one process with
 * no surface to hand across a binder.
 *
 * The sequence is: make a display with nothing on it, put miniMont's own backdrop there, encode
 * what the desktop draws, and send it to whichever AirMate client says hello. This process draws
 * no desktop itself — it makes the screen, and it does the things the app is not allowed to do.
 *
 * It also carries a control stream. The app writes single-line commands to our stdin and reads
 * single-line events from our stdout, over the ADB shell that started us — one pipe, already open,
 * and no second socket to authenticate.
 */
public final class Server {
    private static final String NAME = "miniMont";

    /** Our own backdrop holds a task on this display and is not an application anybody opened. */
    private static final String OURS = "com.minimont";

    private final int dpi;
    private final boolean freeform;
    private final boolean preferHevc;
    /** Whether One UI is invited to put its own desktop here. Off is the whole point of miniMont. */
    private final boolean decorations;
    /** miniMont's own backdrop activity, as `package/class`, or empty for a bare display. */
    private final String backdrop;
    /** An explicit virtual-display flag word, or zero to let MontDisplay work one out. */
    private final int flagOverride;
    /** What shows wherever the desktop draws nothing, as 0xRRGGBB. */
    private final int background;

    private final ExecutorService session = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "miniMont-Session");
        thread.setDaemon(true);
        return thread;
    });
    private final Random random = new Random();

    /** What the app has launched on the desktop, in the order it launched it. */
    private final java.util.Set<String> launched =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    /** The last set reported, so the app is told when it changes and not once a second regardless. */
    private java.util.Set<String> reported = new java.util.LinkedHashSet<>();

    private Transport transport;
    private Encoder encoder;
    private FrameRepeater repeater;
    private MontDisplay display;
    private Input input;
    /** Read by the control thread and written by the session thread, so it is read as it is. */
    private volatile Pointer pointer;

    /**
     * The next click, whichever surface it arrives from, is a right click.
     *
     * The tablet's touchscreen can only say "tap" — no press, no release, no duration — so a hold
     * cannot be recognised there however long anybody holds. What can be recognised is a deliberate
     * arming beforehand, and then any click at all becomes the one that was asked for. It is held
     * here rather than in the app because a tap on the tablet never passes through the app.
     */
    private volatile boolean armRight;

    /** Which button the current press turned into, so its release can match it. */
    private int heldButton;
    private Desktops desks;

    private int width;
    private int height;
    private volatile long sessionId;
    private long frameId;
    private volatile boolean wantRunning = true;

    /** The client we have already refitted for, so a repeated announcement is not a restart. */
    private long fittedFor = -1;

    /** Whether a session has ever come up, which decides whether stdin EOF means anything. */
    private volatile boolean everStarted;

    private Server(int width, int height, int dpi, boolean freeform, boolean preferHevc,
                   int flagOverride, int background, boolean decorations, String backdrop) {
        this.decorations = decorations;
        this.backdrop = backdrop;
        this.flagOverride = flagOverride;
        this.background = background;
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.freeform = freeform;
        this.preferHevc = preferHevc;
    }

    public static void main(String[] args) throws Exception {
        for (String argument : args) {
            if ("dumpflags".equals(argument)) {
                dumpFlags();
                return;
            }
        }
        int width = 1920, height = 1080, dpi = 160, flagOverride = 0, background = 0;
        boolean freeform = true, hevc = false, decorations = false;
        String backdrop = "";
        for (String argument : args) {
            if (argument.startsWith("size=")) {
                String[] parts = argument.substring(5).split("x");
                width = Integer.parseInt(parts[0]);
                height = Integer.parseInt(parts[1]);
            } else if (argument.startsWith("dpi=")) {
                dpi = Integer.parseInt(argument.substring(4));
            } else if (argument.startsWith("freeform=")) {
                freeform = !"false".equals(argument.substring(9));
            } else if (argument.startsWith("hevc=")) {
                hevc = "true".equals(argument.substring(5));
            } else if (argument.startsWith("decor=")) {
                decorations = "true".equals(argument.substring(6));
            } else if (argument.startsWith("backdrop=")) {
                backdrop = argument.substring(9);
            } else if (argument.startsWith("bg=")) {
                background = Integer.parseInt(argument.substring(3), 16);
            } else if (argument.startsWith("flags=")) {
                // Long, then narrowed: the Samsung-private flags run into the sign bit and
                // Integer.decode refuses anything above 0x7fffffff.
                flagOverride = (int) (long) Long.decode(argument.substring(6));
            }
        }
        // app_process hands us a bare thread. The framework wants a main Looper on it, and
        // ActivityThread.systemMain() builds a Handler against whichever thread calls it — so the
        // context has to be warmed here, on this thread, and not later on a worker that has none.
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        FakeContext.get();
        new Server(width, height, dpi, freeform, hevc, flagOverride, background, decorations,
                backdrop).run();
    }

    /**
     * Every virtual-display flag this device's framework actually defines.
     *
     * Written down from the device rather than from the SDK, because the flags that matter here are
     * hidden, vary by release, and Samsung adds its own. Guessing which one controls a wallpaper is
     * slower than reading the list.
     */
    private static void dumpFlags() {
        java.lang.reflect.Field[] fields =
                android.hardware.display.DisplayManager.class.getDeclaredFields();
        java.util.TreeMap<Integer, String> byValue = new java.util.TreeMap<>();
        for (java.lang.reflect.Field field : fields) {
            if (!field.getName().startsWith("VIRTUAL_DISPLAY_FLAG")) continue;
            try {
                field.setAccessible(true);
                byValue.put(field.getInt(null), field.getName());
            } catch (Exception ignored) {
                // A field that will not read is one we could not have used anyway.
            }
        }
        for (java.util.Map.Entry<Integer, String> entry : byValue.entrySet()) {
            Ln.i("FLAGS", String.format("0x%05x  %s", entry.getKey(), entry.getValue()));
        }
    }

    private void run() throws Exception {
        Ln.i("HOST", "miniMont host starting: " + width + "x" + height + "/" + dpi
                + (freeform ? " freeform" : " fullscreen"));

        transport = new Transport(new Transport.Listener() {
            @Override
            public void onCommand(Protocol.Command command) {
                session.execute(() -> perform(command));
            }

            @Override
            public void onClientChanged() {
                // A client arriving mid-GOP has nothing it can decode and no way to ask for more.
                session.execute(() -> {
                    if (encoder != null) encoder.requestKeyframe();
                });
            }
        });
        Thread network = new Thread(transport, "miniMont-Network");
        network.setDaemon(true);
        network.start();

        Runtime.getRuntime().addShutdownHook(new Thread(this::stopSession));
        watchLauncher();

        session.execute(this::startSession);

        Thread status = new Thread(this::statusLoop, "miniMont-Status");
        status.setDaemon(true);
        status.start();

        // The main thread stays on its Looper so the framework handlers built above keep running.
        Looper.loop();
    }

    /**
     * Leave when whoever started us does.
     *
     * A shell process outlives the `adb shell` that launched it: closing the connection does not
     * signal it, so every abandoned run keeps its virtual display and its hardware encoder for as
     * long as the phone stays up. A handful of those and `MediaCodec.start` begins failing, because
     * the device has only a few encoders and they are all held by processes nobody can see.
     *
     * End-of-input on stdin is how the launcher going away announces itself. It is ignored before
     * the first session starts, because a launcher that redirects stdin from nowhere reports EOF
     * immediately and would otherwise shut us down a moment after boot.
     */
    private void watchLauncher() {
        Thread watchdog = new Thread(() -> {
            try {
                java.io.BufferedReader reader =
                        new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String line;
                while ((line = reader.readLine()) != null) {
                    String command = line.trim();
                    if (command.isEmpty()) continue;
                    // Pointer verbs run on this thread. Everything else — launching, closing,
                    // resizing — goes to the session thread, and a launch waits there for the
                    // framework to finish moving a window. Sharing one thread would stall the
                    // cursor for a second every time somebody opened something, which is the one
                    // delay a pointer cannot have.
                    if (hot(command)) control(command);
                    else session.execute(() -> control(command));
                }
            } catch (Exception ignored) {
                // A broken pipe says the same thing EOF does.
            }
            if (!everStarted) {
                Ln.i("HOST", "no control stream; staying up until killed");
                return;
            }
            Ln.i("HOST", "launcher went away; releasing the display and exiting");
            stopSession();
            System.exit(0);
        }, "miniMont-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Whether a line is one of the verbs sent at the rate a finger moves. */
    private static boolean hot(String line) {
        if (line.length() < 2 || line.charAt(1) != ' ') return false;
        switch (line.charAt(0)) {
            case 'p': case 'b': case 'w': case 'k': case 't': return true;
            default: return false;
        }
    }

    /**
     * One line in, one action.
     *
     * Deliberately a text protocol on a pipe that is already open and already authenticated by the
     * ADB pairing. A second socket would need its own listener, its own address and its own answer
     * to who is allowed to talk to it, to carry four verbs.
     */
    private void control(String line) {
        int space = line.indexOf(' ');
        String verb = space < 0 ? line : line.substring(0, space);
        String argument = space < 0 ? "" : line.substring(space + 1).trim();
        switch (verb) {
            case "launch": {
                if (display == null || argument.isEmpty()) return;
                // Already open on another desktop? Go there rather than dragging it here. A
                // desktop that gives up its windows the moment you ask for one somewhere else is
                // not holding an arrangement, it is holding a list.
                if (desks != null) {
                    int where = desks.desktopOf(packageOf(argument));
                    if (where >= 0 && where != desks.current()) {
                        desks.switchTo(where);
                        announce(true);
                        return;
                    }
                }
                if (Desktop.launch(display.id(), argument)) {
                    launched.add(packageOf(argument));
                    announce(true);
                }
                break;
            }
            case "spawn": {
                if (display == null || argument.isEmpty()) return;
                Desktop.spawn(display.id(), argument);
                announce(true);
                break;
            }
            case "close": {
                if (argument.isEmpty()) return;
                Desktop.close(argument);
                launched.remove(argument);
                announce(true);
                break;
            }
            case "desk": {
                if (desks == null) return;
                String[] parts = argument.split(" ");
                switch (parts[0]) {
                    case "add": desks.add(); break;
                    case "remove": desks.remove(Integer.parseInt(parts[1])); break;
                    case "show": desks.switchTo(Integer.parseInt(parts[1])); break;
                    default: break;
                }
                announce(true);
                break;
            }
            case "arrange": {
                int gap = argument.indexOf(' ');
                if (gap < 0) return;
                Desktop.arrange(argument.substring(0, gap), argument.substring(gap + 1).trim());
                break;
            }
            case "open": {
                if (display == null || argument.isEmpty()) return;
                int gap = argument.indexOf(' ');
                String action = gap < 0 ? argument : argument.substring(0, gap);
                String data = gap < 0 ? null : argument.substring(gap + 1).trim();
                Desktop.open(display.id(), action, data);
                break;
            }
            case "area": {
                String[] parts = argument.split(" ");
                if (parts.length == 4) {
                    Desktop.setArea(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                }
                break;
            }
            case "fit": {
                if (!argument.isEmpty()) Desktop.fit(argument);
                break;
            }
            case "arm": {
                armRight = true;
                Ln.i("EVENT", "armed 1");
                break;
            }
            case "wifi": {
                Desktop.wifi("1".equals(argument));
                break;
            }
            case "saver": {
                Desktop.batterySaver("1".equals(argument));
                break;
            }
            case "backdrop": {
                if (display != null && !backdrop.isEmpty()) Desktop.showDesktop(display.id(), backdrop);
                break;
            }
            case "running": {
                announce(true);
                break;
            }
            // The pointer verbs are short because they are the only ones sent at the rate a finger
            // moves. Everything else on this stream happens once per human decision.
            // Absolute, not relative. One end keeps the position and the other obeys it, so the
            // arrow on screen and the pointer the framework believes in cannot drift apart.
            case "p": {
                if (pointer == null) return;
                String[] parts = argument.split(" ");
                pointer.moveTo(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]));
                break;
            }
            case "b": {
                if (pointer == null) return;
                String[] parts = argument.split(" ");
                int button = Integer.parseInt(parts[0]);
                boolean down = "1".equals(parts[1]);
                // A release is whatever its own press turned out to be.
                //
                // Press and release were converted independently, so anything that disarmed
                // between them — a tap arriving from the tablet, say — pressed button two and
                // released button one. Button two then stayed down for ever: menus opened and
                // would not close, and nothing else worked either, because something was always
                // being held.
                if (down) {
                    if (armRight && button == 1) button = 2;
                    heldButton = button;
                } else {
                    if (heldButton != 0) button = heldButton;
                    heldButton = 0;
                    if (armRight && button == 2) disarm();
                }
                float x = pointer.x();
                float y = pointer.y();

                // The button first, always, and nothing between the press arriving and the press
                // being sent.
                //
                // Snapping used to run in this gap. It reads the task list by reflection and can
                // end in a process spawn, so a release could be delayed by hundreds of
                // milliseconds — and if anything in it threw, the release was never sent at all and
                // the button stayed down for good. Nothing clicked again, which is a long way from
                // "the snap did not work".
                pointer.button(button, down);

                if (button == 1) {
                    if (down) session.execute(() -> rememberDrag(x, y));
                    else session.execute(() -> snapIfDragged(x, y));
                }
                break;
            }
            case "w": {
                if (pointer == null) return;
                String[] parts = argument.split(" ");
                pointer.scroll(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]));
                break;
            }
            case "k": {
                if (pointer == null) return;
                String[] parts = argument.split(" ");
                pointer.tap(Integer.parseInt(parts[0]),
                        parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
                break;
            }
            case "t": {
                if (pointer != null && !argument.isEmpty()) pointer.type(argument);
                break;
            }
            default:
                Ln.i("HOST", "unknown command: " + line);
        }
    }

    /**
     * Tell the app which of the apps it launched are still there.
     *
     * Sent when it changes rather than once a second, because the dock redrawing on a timer is a
     * dock that flickers for reasons nobody can see. @param force sends it regardless, for the
     * moment immediately after a launch or a close when the answer is the whole point.
     */
    private void announce(boolean force) {
        if (display == null) return;
        // What has a window here, which is what the taskbar is a list of. Asking the process list
        // instead answered a different question: Chrome's process outlives its window, so closing
        // the window left it in the taskbar with nothing on screen behind it.
        java.util.LinkedHashSet<String> shown =
                new java.util.LinkedHashSet<>(Tasks.onDisplay(display.id(), OURS));
        if (!force && shown.equals(reported)) return;
        reported = shown;
        Ln.i("EVENT", "running " + String.join(",", shown));
        // Which desktop is showing, how many there are, and what is on each.
        if (desks != null) {
            Ln.i("EVENT", "desks " + desks.current() + " " + desks.describe());
        }
    }

    /**
     * What the pointer was on when the button went down, and where that window was.
     *
     * Android owns the window drag — its own caption is what you grab — so miniMont cannot watch
     * one happen. It can watch what it already knows: which window the press landed in, where that
     * window was at the time, and where the pointer ended up.
     */
    private int dragTask = -1;
    private int[] dragBounds;

    private void rememberDrag(float pressX, float pressY) {
        dragTask = -1;
        dragBounds = null;
        if (display == null) return;
        try {
            int x = (int) pressX;
            int y = (int) pressY;
            for (int[] window : Tasks.windows(display.id(), OURS)) {
                if (x >= window[1] && x <= window[3] && y >= window[2] && y <= window[4]) {
                    dragTask = window[0];
                    dragBounds = new int[] { window[1], window[2], window[3], window[4] };
                    return;
                }
            }
        } catch (Throwable failure) {
            Ln.e("DESKTOP", "could not note what was under the press", failure);
        }
    }

    /**
     * Snap, if a window was actually dragged and the pointer finished at an edge.
     *
     * Both halves of that matter. Requiring the window to have *moved* is what stops a selection
     * dragged to the side of a document from throwing the document across the screen; requiring the
     * pointer to finish at an edge is what makes it a deliberate gesture rather than an accident of
     * where somebody let go.
     */
    private void snapIfDragged(float releaseX, float releaseY) {
        if (display == null || dragTask < 0 || dragBounds == null) return;
        int task = dragTask;
        int[] before = dragBounds;
        dragTask = -1;
        dragBounds = null;

        try {
            boolean moved = false;
            for (int[] window : Tasks.windows(display.id(), OURS)) {
                if (window[0] != task) continue;
                moved = window[1] != before[0] || window[2] != before[1];
                break;
            }
            if (!moved) return;

            int x = (int) releaseX;
            int y = (int) releaseY;
            if (y <= EDGE) Desktop.arrange(task, "fill");
            else if (x <= EDGE) Desktop.arrange(task, "left");
            else if (x >= width - EDGE) Desktop.arrange(task, "right");
        } catch (Throwable failure) {
            Ln.e("DESKTOP", "could not snap task " + task, failure);
        }
    }

    /** How close to an edge counts as being at it. */
    private static final int EDGE = 12;

    private void disarm() {
        armRight = false;
        Ln.i("EVENT", "armed 0");
    }

    private static String packageOf(String component) {
        int slash = component.indexOf('/');
        return slash < 0 ? component : component.substring(0, slash);
    }

    /** Status once a second, which is the only thing the client can describe a stopped host from. */
    private void statusLoop() {
        long previous = 0;
        long previousDropped = 0;
        int tick = 0;
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException stopped) {
                return;
            }
            transport.sendStatus(display != null, false, width, height, Diagnostics.encoded);

            // Anything dropped means the client is holding a frame with a hole in it, and it will
            // keep holding it until a keyframe arrives. Waiting for the scheduled one leaves the
            // mess on screen for up to a second; asking now costs one large frame and ends it.
            long dropped = Diagnostics.droppedNetwork;
            if (dropped > previousDropped) {
                previousDropped = dropped;
                Encoder current = encoder;
                if (current != null) current.requestKeyframe();
            }
            // The dock's own heartbeat. Nothing is sent unless the answer changed.
            session.execute(() -> announce(false));
            // And a look for anything the system's own caption has just filled the screen with.
            session.execute(() -> {
                if (display != null) Desktop.keepOffTheBar(display.id(), width, height);
            });

            // The floor, put back if it has gone.
            //
            // Everything on this display stands on the backdrop: the wallpaper, the icons, the
            // widgets, and the only thing the pointer has to hover over when no app is open. Lose
            // it and the desktop is a black rectangle where launches land oddly and the cursor
            // wanders. It should never go now that back no longer closes it, but a desktop that
            // cannot survive losing its floor is one bad key away from needing a restart.
            if (tick % 3 == 0) session.execute(this::keepTheFloor);
            // Every fifth tick, in frames per second rather than a running total: "no picture" has
            // several causes that look identical from here, and the rate tells them apart — nothing
            // encoded is a dead display, encoded but dropped is a socket that will not take them.
            if (++tick % 5 == 0) {
                long encoded = Diagnostics.encoded;
                Ln.i("VIDEO", "encoded=" + encoded + " (" + (encoded - previous) / 5 + "/s)"
                        + " droppedNetwork=" + Diagnostics.droppedNetwork
                        + " client=" + (transport.paired() ? "paired" : "none"));
                previous = encoded;
            }
        }
    }

    private void keepTheFloor() {
        if (display == null || backdrop.isEmpty()) return;
        int[] found = Tasks.find(OURS);
        if (found != null && found[1] == display.id()) return;
        Ln.i("HOST", "the backdrop is gone; putting it back");
        Desktop.backdrop(display.id(), backdrop);
    }

    private void startSession() {
        if (display != null) return;
        try {
            sessionId = random.nextLong();
            frameId = 0;
            encoder = new Encoder(width, height, preferHevc, this::onAccessUnit);

            // The display normally draws straight into the encoder. It draws into the repeat pass
            // instead when there is one, so a motionless desktop keeps sending. Falling back is
            // safe and is exactly the behaviour that shipped before the pass existed: a still
            // desktop simply stops sending until something on it moves.
            android.view.Surface target = encoder.surface();
            try {
                repeater = FrameRepeater.start(width, height, encoder.surface(), background);
                target = repeater.displaySurface();
            } catch (Throwable failure) {
                Ln.e("VIDEO", "no frame repeater; a still desktop will stop sending", failure);
                repeater = null;
            }

            display = MontDisplay.create(NAME, width, height, dpi, target, flagOverride, decorations);
            if (freeform) display.enableFreeform();
            input = new Input(display.id(), width, height);
            pointer = new Pointer(display.id(), width, height);
            if (desks == null) desks = new Desktops(width, height, dpi, OURS);
            desks.onDisplay(display.id());
            if (!pointer.ready()) {
                Ln.i("HOST", "POINTER FAILED — the cover screen will not be able to drive this");
            }
            everStarted = true;
            // The backdrop goes on immediately. An empty trusted display is a black rectangle that
            // gives no sign of whether any of this worked, and the wallpaper arriving is the first
            // honest confirmation that a display exists and will hold a window.
            // A provisional apps area, until the app measures its taskbar and says. Without one,
            // the first window opened before the chrome had attached was placed nowhere and kept
            // whatever size the app chose for itself — which for Chrome is a phone.
            Desktop.setArea(8, 8, width - 8, height - 8);
            Desktop.allowWidgets(OURS);
            if (!backdrop.isEmpty() && !Desktop.backdrop(display.id(), backdrop)) {
                // The one failure that must never be a black screen with no explanation. A display
                // without system decorations that will not take an activity is the project's
                // central unknown, and this is where it would show up first.
                Ln.i("HOST", "BACKDROP FAILED — this display would not accept a window");
            }
            Ln.i("HOST", "SESSION READY — waiting for an AirMate client to say hello");
        } catch (Throwable error) {
            Ln.e("HOST", "could not start the session", error);
            stopSession();
        }
    }

    private void stopSession() {
        // Torn down in the order things depend on each other: the display draws into the encoder's
        // surface, so the display goes first or the encoder is released underneath a live producer.
        if (input != null) { input.close(); input = null; }
        if (desks != null) desks.release();
        pointer = null;
        if (display != null) { display.release(); display = null; }
        if (repeater != null) { repeater.close(); repeater = null; }
        if (encoder != null) { encoder.close(); encoder = null; }
        Ln.i("HOST", "session stopped");
    }

    private void perform(Protocol.Command command) {
        switch (command.type) {
            case Protocol.TYPE_START:
                wantRunning = true;
                startSession();
                break;
            case Protocol.TYPE_STOP:
                wantRunning = false;
                stopSession();
                break;
            case Protocol.TYPE_REQUEST_IDR:
                if (encoder != null) encoder.requestKeyframe();
                break;
            case Protocol.TYPE_SET_DISPLAY:
                if (command.width == width && command.height == height) return;
                Ln.i("HOST", "resizing to " + command.width + "x" + command.height);
                stopSession();
                width = command.width;
                height = command.height;
                if (wantRunning) startSession();
                break;
            case Protocol.TYPE_CLICK:
                if (armRight && pointer != null) {
                    // Placed and pressed rather than tapped: a tap is a finger, and a finger has no
                    // second button to press.
                    pointer.moveTo(
                            (float) command.x * width / 65535f,
                            (float) command.y * height / 65535f);
                    pointer.button(2, true);
                    pointer.button(2, false);
                    disarm();
                    break;
                }
                // Logged to Android's own log as well as ours, so what the tablet actually sends
                // can be read from outside this process. A hold on a touchscreen can only become a
                // right click if the client says enough for one to be recognised, and nothing so
                // far says whether it does.
                android.util.Log.i("miniMont", "tablet click " + command.x + "," + command.y);
                if (input != null) input.click(command.x, command.y);
                break;
            case Protocol.TYPE_SCROLL:
                android.util.Log.i("miniMont", "tablet scroll phase=" + command.phase
                        + " at " + command.x + "," + command.y
                        + " by " + command.dx + "," + command.dy);
                if (input != null) input.scroll(command.phase, command.x, command.y, command.dx, command.dy);
                break;
            case Protocol.TYPE_CLIENT_DISPLAY:
                onClientDisplay(command);
                break;
            default:
                break;
        }
    }

    /**
     * What the client is, and whether we are sending it something it can actually decode.
     *
     * A size above the client's decoder ceiling is not a worse picture, it is no picture: the
     * hardware answers with an error and the codec dies for the rest of the session. So this is the
     * one case where the host overrides the size it was asked for — never to improve it, only when
     * the alternative is a black screen.
     */
    private void onClientDisplay(Protocol.Command command) {
        Ln.i("HOST", "client panel " + command.width + "x" + command.height
                + ", decoder ceiling " + command.maxWidth + "x" + command.maxHeight);
        if (command.maxWidth <= 0 || command.maxHeight <= 0) return;
        if (width <= command.maxWidth && height <= command.maxHeight) return;
        if (fittedFor == command.width * 100000L + command.height) return;

        int[] best = fit(command.width, command.height, command.maxWidth, command.maxHeight);
        if (best == null) {
            Ln.e("HOST", "no size fits a " + command.maxWidth + "x" + command.maxHeight
                    + " decoder; leaving " + width + "x" + height + " alone", null);
            return;
        }
        // Remembered so a client that repeats itself once a second does not restart the desktop
        // once a second with it.
        fittedFor = command.width * 100000L + command.height;
        Ln.i("HOST", width + "x" + height + " is past this client's decoder; refitting to "
                + best[0] + "x" + best[1]);
        stopSession();
        width = best[0];
        height = best[1];
        if (wantRunning) startSession();
    }

    /**
     * The largest ordinary desktop size the client's decoder will accept.
     *
     * This used to derive sizes from the client's own panel — 2000 x 1200 scaled down and snapped
     * to sixteen, which produces shapes like 1808 x 1088. They are inside every stated limit and
     * they decode into a black screen with fragments scattered across it: encoders and decoders
     * agree on the sizes everybody uses and quietly disagree on the ones nobody does.
     *
     * So the ladder is fixed, and it is the sizes a desktop is actually run at. The picture is
     * letterboxed on a panel of a different shape, which is a visible compromise rather than an
     * invisible corruption.
     */
    private static final int[][] LADDER = {
            { 1920, 1080 },
            { 1600, 900 },
            { 1280, 800 },
            { 1280, 720 },
            { 1024, 768 },
    };

    private static int[] fit(int panelWidth, int panelHeight, int maxWidth, int maxHeight) {
        for (int[] candidate : LADDER) {
            if (candidate[0] <= maxWidth && candidate[1] <= maxHeight) return candidate;
        }
        return null;
    }

    private void onAccessUnit(byte[] data, int length, long captureNanos, boolean keyframe, boolean hevc) {
        transport.sendVideo(data, length, sessionId, ++frameId, captureNanos, keyframe, hevc);
    }
}
