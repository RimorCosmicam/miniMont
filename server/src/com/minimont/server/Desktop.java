package com.minimont.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The privileged verbs the desktop needs and the app cannot issue for itself.
 *
 * An ordinary app can neither launch somebody else's activity onto a display it does not own nor
 * stop it afterwards. All of that lives here, on the shell side of the line, and the app asks for
 * it by name over the control stream.
 *
 * These are shell commands rather than framework calls, deliberately, for now. `am` and `wm` are
 * the versions that cannot break, and a launch or a close happens once per user action where a
 * process spawn is invisible. The moment something has to happen per motion event — dragging a
 * window, sizing one — this file stops being adequate and the framework calls have to be found.
 * That day is written down in docs/MEASUREMENTS.md rather than discovered in a profiler.
 */
public final class Desktop {

    /** ActivityManager's windowing modes, as `am start --windowingMode` wants them. */
    private static final int FULLSCREEN = 1;
    private static final int FREEFORM = 5;

    /** Intent flags, for the launch that has to make its own task rather than reuse one. */
    private static final int NEW_TASK = 0x10000000;
    private static final int MULTIPLE_TASK = 0x08000000;

    /**
     * Where windows are allowed to open: the screen, less the taskbar and its own padding.
     *
     * Sent by the app, because the app is the thing that draws the taskbar and therefore the only
     * thing that knows how tall it is. Zero until it says, and while it is zero nothing is clamped —
     * guessing at an area and moving somebody's window into it would be worse than leaving it.
     */
    private static volatile int[] area = { 0, 0, 0, 0 };

    public static void setArea(int left, int top, int right, int bottom) {
        area = new int[] { left, top, right, bottom };
        Ln.i("DESKTOP", "apps area " + left + "," + top + " - " + right + "," + bottom);
    }

    private Desktop() {
    }

    /**
     * Put miniMont's own backdrop on the display, full screen and at the bottom of everything.
     *
     * It goes on first and is never closed, so the wallpaper is what shows wherever no window is,
     * and so the display always has something on it — an empty trusted display is a black
     * rectangle that gives no sign of whether anything worked.
     */
    public static boolean backdrop(int displayId, String component) {
        return start(displayId, component, FULLSCREEN, true);
    }

    /**
     * Open one of the phone's own screens, named by what it does rather than by which class does it.
     *
     * Samsung's settings screens have no component name worth writing down — they move and get
     * renamed — but they all answer an action. Launched like anything else, so they land on the
     * desktop as a window and are fitted into the apps area with the rest.
     */
    /**
     * Open an app on the desktop, and make sure that is where it opened.
     *
     * `am start --display` only means what it says when the app has no task already. When it has
     * one, the launch brings that task forward on the screen it is already on — which is why
     * Samsung Internet arrived on the miniMont display and Chrome arrived on the cover screen,
     * where it was already running.
     *
     * So the launch is checked rather than trusted, and there are three answers in order of how
     * much they disturb: leave it if it landed here, move it if it did not, and only if the
     * framework refuses to move it, open a second window of it here. Two windows is a worse answer
     * than one in the right place, and a better one than a desktop whose apps open behind you.
     */
    /**
     * A second window of something, on the desktop you are looking at.
     *
     * Asked for explicitly, because the plain launch deliberately does the opposite: it takes you
     * to wherever the application already is. This one insists on a new task here.
     *
     * An application that will not have two — most will not, their launch mode forbids it — simply
     * brings its existing window across instead. That is the framework's answer rather than ours,
     * and it is a reasonable one: you asked to have it here, and here it is.
     */
    public static boolean spawn(int displayId, String component) {
        boolean started = start(displayId, component, FREEFORM, false, NEW_TASK | MULTIPLE_TASK);
        place(packageOf(component));
        return started;
    }

    public static boolean open(int displayId, String action, String data) {
        String command = "am start -a " + action
                + (data == null || data.isEmpty() ? "" : " -d '" + data + "'")
                + " --display " + displayId
                + " --windowingMode " + FREEFORM
                + " -f 0x" + Integer.toHexString(NEW_TASK);
        String output = run(command);
        boolean failed = output.contains("Error:") || output.contains("Exception");
        Ln.i("DESKTOP", (failed ? "could not open " : "opened ") + action
                + (failed ? ": " + output.trim() : ""));
        return !failed;
    }

    public static boolean launch(int displayId, String component) {
        String packageName = packageOf(component);
        start(displayId, component, FREEFORM, false);

        int[] found = settle(packageName);
        if (found == null) {
            // Said out loud, because "it landed correctly" and "the framework would not tell us
            // where it landed" look identical from here and want completely different fixes.
            Ln.i("DESKTOP", "no task reported for " + packageName + "; leaving the launch alone");
            return true;
        }
        if (found[1] == displayId) {
            Ln.i("DESKTOP", packageName + " is on display " + displayId + ", task " + found[0]);
            place(packageName);
            return true;
        }

        Ln.i("DESKTOP", packageName + " opened on display " + found[1] + ", not " + displayId);
        if (Tasks.moveToDisplay(found[0], displayId)) {
            int[] after = settle(packageName);
            if (after != null && after[1] == displayId) {
                Ln.i("DESKTOP", "moved " + packageName + " to display " + displayId);
                place(packageName);
                return true;
            }
        }

        Ln.i("DESKTOP", "could not move " + packageName + "; opening a new window here instead");
        return start(displayId, component, FREEFORM, false, NEW_TASK | MULTIPLE_TASK);
    }

    /**
     * Give a freshly opened window a desktop-sized one.
     *
     * An app decides its own freeform size, and what it decides is usually a phone: Chrome opens
     * narrow enough to switch to its phone layout, which on a sixteen hundred pixel display is a
     * column of website down the middle of a desk. Before the apps area existed it opened *larger*
     * than the screen and got the tablet layout by accident — the layout was right and the size was
     * unusable.
     *
     * So a launch is placed rather than merely clamped: most of the area, centred, which is wide
     * enough that anything using width breakpoints lands on its large one. A window is only placed
     * once, when it opens; after that it is yours and fit() will not touch its shape.
     */
    public static boolean place(String packageName) {
        int[] safe = area;
        if (safe[2] <= safe[0] || safe[3] <= safe[1]) return false;

        int[] found = Tasks.find(packageName);
        if (found == null) return false;

        int width = (int) ((safe[2] - safe[0]) * 0.78f);
        int height = (int) ((safe[3] - safe[1]) * 0.84f);
        int left = safe[0] + ((safe[2] - safe[0]) - width) / 2;
        int top = safe[1] + ((safe[3] - safe[1]) - height) / 2;
        Ln.i("DESKTOP", "placing " + packageName + " at " + left + "," + top
                + " " + width + "x" + height);
        return Tasks.resize(found[0], left, top, left + width, top + height);
    }

    /**
     * Put a window back inside the apps area.
     *
     * Never larger than the area and never outside it, and otherwise left exactly as it was. An app
     * that opens small stays small; an app that opens bigger than the screen — which Chrome does —
     * is brought back to something you can reach the edges of.
     *
     * This runs on every launch and is also a thing you can ask for by hand, because the window that
     * needs it most is the one already open with its corners off the screen.
     */
    public static boolean fit(String packageName) {
        int[] safe = area;
        if (safe[2] <= safe[0] || safe[3] <= safe[1]) return false;

        int[] found = Tasks.find(packageName);
        if (found == null) return false;
        int taskId = found[0];

        int left = found[2], top = found[3], right = found[4], bottom = found[5];
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) {
            // Bounds unknown. Give it the area itself rather than guessing at a shape.
            return Tasks.resize(taskId, safe[0], safe[1], safe[2], safe[3]);
        }

        width = Math.min(width, safe[2] - safe[0]);
        height = Math.min(height, safe[3] - safe[1]);
        left = clamp(left, safe[0], safe[2] - width);
        top = clamp(top, safe[1], safe[3] - height);
        if (left == found[2] && top == found[3]
                && width == found[4] - found[2] && height == found[5] - found[3]) {
            return true;
        }
        Ln.i("DESKTOP", "fitting " + packageName + " to " + left + "," + top
                + " " + width + "x" + height);
        return Tasks.resize(taskId, left, top, left + width, top + height);
    }

    /**
     * Put a window in one of the edges' regions.
     *
     * The regions are the apps area, halved — so a filled window leaves the same gap above the
     * taskbar that the taskbar leaves below itself, and a half fills exactly half of what is left.
     */
    /** The same, found by package, which is what a taskbar knows about an application. */
    public static boolean arrange(String packageName, String where) {
        int[] found = Tasks.find(packageName);
        if (found == null) return false;
        return arrange(found[0], where);
    }

    public static boolean arrange(int taskId, String where) {
        int[] safe = area;
        if (safe[2] <= safe[0] || safe[3] <= safe[1]) return false;
        int left = safe[0], top = safe[1], right = safe[2], bottom = safe[3];
        int middle = (left + right) / 2;
        int centre = (top + bottom) / 2;
        switch (where) {
            case "fill": break;
            case "left": right = middle; break;
            case "right": left = middle; break;
            case "top": bottom = centre; break;
            case "bottom": top = centre; break;
            case "tl": right = middle; bottom = centre; break;
            case "tr": left = middle; bottom = centre; break;
            case "bl": right = middle; top = centre; break;
            case "br": left = middle; top = centre; break;
            default: return false;
        }
        Ln.i("DESKTOP", "snapping task " + taskId + " " + where);
        return Tasks.resize(taskId, left, top, right, bottom);
    }

    /**
     * Put back anything Android has just filled the screen with.
     *
     * Its own caption offers a maximise, and that maximise is the *display* — it fills every pixel,
     * so the window ends up under the taskbar with its bottom edge somewhere behind the clock. We
     * cannot remove that control, so the answer is to notice what it did and undo it.
     *
     * Only an exact fill is touched. A window dragged half off the edge is somebody putting it
     * there on purpose and is left alone; a window that is precisely the size of the display is one
     * nobody dragged, because you cannot drag a window to the pixel.
     */
    public static void keepOffTheBar(int displayId, int width, int height) {
        int[] safe = area;
        if (safe[2] <= safe[0] || safe[3] <= safe[1]) return;
        for (int[] window : Tasks.windows(displayId, "com.minimont")) {
            boolean filled = window[1] <= SLACK && window[2] <= SLACK
                    && window[3] >= width - SLACK && window[4] >= height - SLACK;
            if (!filled) continue;
            Ln.i("DESKTOP", "task " + window[0] + " was filled to the display; holding it off the bar");
            Tasks.resize(window[0], safe[0], safe[1], safe[2], safe[3]);
        }
    }

    /** How far off the display's own edges still counts as filling it. */
    private static final int SLACK = 8;

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }

    /**
     * Where a package's window is, once the framework has finished putting it there.
     *
     * A launch is not instant and the task list says so — asked too early it reports the task on
     * the display it is leaving, which would send us chasing a window that was already on its way.
     */
    private static int[] settle(String packageName) {
        for (int attempt = 0; attempt < 6; attempt++) {
            int[] found = Tasks.find(packageName);
            if (found != null) return found;
            try {
                Thread.sleep(150);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static boolean start(int displayId, String component, int windowingMode, boolean home) {
        return start(displayId, component, windowingMode, home, 0);
    }

    private static boolean start(int displayId, String component, int windowingMode, boolean home,
                                 int flags) {
        StringBuilder command = new StringBuilder("am start")
                .append(" --display ").append(displayId)
                .append(" --windowingMode ").append(windowingMode)
                // Quoted, because these commands go through `sh -c` and a component name is full
                // of things a shell has opinions about. YouTube's launcher activity is
                // `.app.honeycomb.Shell$HomeActivity`; unquoted, the shell expanded $HomeActivity
                // to nothing and started `...Shell`, which does not exist. It failed silently for
                // every app whose launcher is an inner class, which is a great many of them.
                .append(" -n '").append(component).append("'");
        if (flags != 0) command.append(" -f 0x").append(Integer.toHexString(flags));
        // The backdrop is started once and then re-shown rather than restarted, so that turning the
        // wallpaper card on and off does not throw away the window every time.
        else if (home) command.append(" -f 0x").append(Integer.toHexString(NEW_TASK));
        String output = run(command.toString());
        boolean failed = output.contains("Error:") || output.contains("Exception");
        Ln.i("DESKTOP", (failed ? "could not launch " : "launched ") + component
                + " on display " + displayId + (failed ? ": " + output.trim() : ""));
        return !failed;
    }

    /**
     * Close an app, and mean it.
     *
     * `force-stop` rather than a polite finish, because "close the window" in a desktop means the
     * program is gone — not backgrounded, not left holding its task for the next launch to inherit.
     * A window that reopens with the state you closed it in is a window that did not close.
     */
    /** The package half of a `package/class` component. */
    static String packageOf(String component) {
        int slash = component.indexOf('/');
        return slash < 0 ? component : component.substring(0, slash);
    }

    public static boolean close(String packageName) {
        String output = run("am force-stop " + packageName);
        Ln.i("DESKTOP", "closed " + packageName + (output.isEmpty() ? "" : ": " + output.trim()));
        return true;
    }

    /**
     * Which of the packages we launched are still alive.
     *
     * One `ps` per second for the whole device, rather than one probe per app: the process list is
     * cheap to read once and the intersection is free, and asking about six apps separately is six
     * spawns to answer one question.
     *
     * This reports *processes*, not windows, which is not the same thing — an app that closes its
     * own last window keeps its process and stays in the dock until it is stopped. Enumerating the
     * display's real tasks needs the framework calls this file does not yet make; the dock is
     * honest about launches and closes today, which is what the first version has to get right.
     */
    public static Set<String> alive(Set<String> packages) {
        if (packages.isEmpty()) return Collections.emptySet();
        Set<String> found = new LinkedHashSet<>();
        String listing = run("ps -A -o NAME");
        for (String line : listing.split("\n")) {
            String name = line.trim();
            // Sub-processes are named `com.example:renderer` and belong to the same app.
            int colon = name.indexOf(':');
            if (colon > 0) name = name.substring(0, colon);
            if (packages.contains(name)) found.add(name);
        }
        return found;
    }

    /**
     * The system switches the desktop is allowed to flip.
     *
     * Named verbs rather than a general shell escape. The app already holds a shell — it started
     * this process — so a `run whatever` command would add no capability, only an unauditable one.
     * These are the two things the desktop actually has a reason to change, and adding a third
     * means adding a line here where it can be seen.
     */
    /**
     * Let miniMont bind widgets without a dialog for each one.
     *
     * Normally an app either answers a system prompt per widget or holds a signature permission it
     * cannot be granted. `appwidget grant` is a shell command and miniMont is holding a shell, so
     * it asks once, at start, and never again.
     */
    public static void allowWidgets(String packageName) {
        // `grantbind`, not `grant`: the verb is spelled differently from every other shell
        // command that hands out a permission, and the wrong one fails with "Unsupported
        // operation" rather than with anything that names the right one.
        String output = run("appwidget grantbind --package " + packageName);
        Ln.i("DESKTOP", "widget binding for " + packageName
                + (output.isBlank() ? " granted" : ": " + output.trim()));
    }

    public static void wifi(boolean on) {
        run("svc wifi " + (on ? "enable" : "disable"));
        Ln.i("DESKTOP", "wifi " + (on ? "on" : "off"));
    }

    public static void batterySaver(boolean on) {
        run("settings put global low_power " + (on ? 1 : 0));
        Ln.i("DESKTOP", "battery saver " + (on ? "on" : "off"));
    }

    private static String run(String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true).start();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            process.waitFor();
        } catch (Exception error) {
            Ln.e("DESKTOP", command, error);
        }
        return output.toString();
    }
}
