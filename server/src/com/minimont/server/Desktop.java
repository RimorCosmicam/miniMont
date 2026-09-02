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
    public static boolean launch(int displayId, String component) {
        String packageName = packageOf(component);
        start(displayId, component, FREEFORM, false);

        int[] found = settle(packageName);
        if (found == null) {
            // Nothing to correct, or nothing the framework will tell us about. The launch stands.
            return true;
        }
        if (found[1] == displayId) return true;

        Ln.i("DESKTOP", packageName + " opened on display " + found[1] + ", not " + displayId);
        if (Tasks.moveToDisplay(found[0], displayId)) {
            int[] after = settle(packageName);
            if (after != null && after[1] == displayId) {
                Ln.i("DESKTOP", "moved " + packageName + " to display " + displayId);
                return true;
            }
        }

        Ln.i("DESKTOP", "could not move " + packageName + "; opening a new window here instead");
        return start(displayId, component, FREEFORM, false, NEW_TASK | MULTIPLE_TASK);
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
                .append(" -n ").append(component);
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
