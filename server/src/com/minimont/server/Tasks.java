package com.minimont.server;

import android.content.ComponentName;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where an app's window actually is, and how to bring it here.
 *
 * `am start --display N` creates a task on that display, and does nothing of the sort when the app
 * already has one somewhere else: it brings the existing task forward, on the screen it is already
 * on. Which is why Samsung Internet opened on the miniMont display and Chrome opened on the cover
 * screen — Chrome was already running there.
 *
 * Every method here is reflection over hidden framework calls, so every one of them is written to
 * fail into the next thing rather than to throw. The names are looked up rather than bound, because
 * these have moved between releases and a rename should cost one capability and not the desktop.
 */
public final class Tasks {

    private Tasks() {
    }

    /**
     * The root task holding a package, as {rootTaskId, displayId, left, top, right, bottom}.
     *
     * The bounds come back as zeroes when the framework will not give them up, which the caller has
     * to treat as "unknown" rather than as "a window at the origin with no size".
     */
    public static int[] find(String packageName) {
        for (Object task : rootTasks()) {
            if (!holds(task, packageName)) continue;
            Integer id = number(task, "taskId");
            Integer display = number(task, "displayId");
            if (id == null || display == null) continue;
            int[] bounds = bounds(task);
            return new int[] { id, display, bounds[0], bounds[1], bounds[2], bounds[3] };
        }
        return null;
    }

    /**
     * Put a task somewhere, in display pixels.
     *
     * Only meaningful in freeform, which is the only mode miniMont launches into. The framework
     * call first and the shell command behind it, the same order and for the same reason as moving
     * one: a resize that happens once per action can afford a process, and one that happens per
     * frame cannot.
     */
    /**
     * Every window on a display, as {taskId, left, top, right, bottom}.
     *
     * Read on the status tick, for one job: noticing when something has been maximised by Android's
     * own caption, which fills the display and knows nothing about a taskbar.
     */
    public static java.util.List<int[]> windows(int displayId, String exclude) {
        java.util.List<int[]> out = new ArrayList<>();
        for (Object task : rootTasks()) {
            Integer display = number(task, "displayId");
            Integer id = number(task, "taskId");
            if (display == null || id == null || display != displayId) continue;
            String name = packageOf(task);
            if (name == null || name.equals(exclude)) continue;
            int[] bounds = bounds(task);
            if (bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) continue;
            out.add(new int[] { id, bounds[0], bounds[1], bounds[2], bounds[3] });
        }
        return out;
    }

    /**
     * Every root task on a display, whatever state it is in.
     *
     * Unlike the window list this does not skip tasks with no bounds, because a task parked on a
     * display with no surface may report none — and those are exactly the ones a desktop switch has
     * to find and bring back.
     */
    public static java.util.List<int[]> tasksOn(int displayId, String exclude) {
        java.util.List<int[]> out = new ArrayList<>();
        for (Object task : rootTasks()) {
            Integer display = number(task, "displayId");
            Integer id = number(task, "taskId");
            if (display == null || id == null || display != displayId) continue;
            String name = packageOf(task);
            if (name == null || name.equals(exclude)) continue;
            out.add(new int[] { id });
        }
        return out;
    }

    /** The packages on a display, in the order the framework lists them. */
    public static java.util.List<String> packagesOn(int displayId, String exclude) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Object task : rootTasks()) {
            Integer display = number(task, "displayId");
            if (display == null || display != displayId) continue;
            String name = packageOf(task);
            if (name != null && !name.equals(exclude)) names.add(name);
        }
        return new ArrayList<>(names);
    }

    public static boolean resize(int taskId, int left, int top, int right, int bottom) {
        try {
            Object service = service();
            Method resize = null;
            for (Method method : Class.forName("android.app.IActivityTaskManager").getMethods()) {
                if (method.getName().equals("resizeTask")) {
                    resize = method;
                    break;
                }
            }
            if (resize != null) {
                Object[] arguments = new Object[resize.getParameterCount()];
                arguments[0] = taskId;
                arguments[1] = new android.graphics.Rect(left, top, right, bottom);
                for (int i = 2; i < arguments.length; i++) arguments[i] = 0;
                resize.invoke(service, arguments);
                return true;
            }
        } catch (Throwable refused) {
            Ln.i("TASKS", "framework would not resize " + taskId + ": " + refused);
        }
        return run("am task resize " + taskId + " " + left + " " + top + " " + right + " " + bottom);
    }

    /** A task's bounds, or four zeroes when the framework will not say. */
    private static int[] bounds(Object task) {
        try {
            Object configuration = field(task, "configuration");
            Object window = configuration == null ? null : field(configuration, "windowConfiguration");
            if (window != null) {
                Method get = window.getClass().getMethod("getBounds");
                get.setAccessible(true);
                Object rect = get.invoke(window);
                if (rect instanceof android.graphics.Rect) {
                    android.graphics.Rect bounds = (android.graphics.Rect) rect;
                    return new int[] { bounds.left, bounds.top, bounds.right, bounds.bottom };
                }
            }
        } catch (Throwable ignored) {
            // Unknown, which the caller treats as unknown.
        }
        return new int[] { 0, 0, 0, 0 };
    }

    /**
     * Bring a root task to a display.
     *
     * The binder call first, because it is the one that does not spawn a process, then the two
     * shell commands that have historically done the same job. MiniDex's own note applies here and
     * is the reason the caller verifies afterwards rather than trusting the answer: Samsung
     * sometimes accepts the binder call and leaves the task exactly where it was.
     */
    public static boolean moveToDisplay(int rootTaskId, int displayId) {
        try {
            Object service = service();
            Method move = Class.forName("android.app.IActivityTaskManager")
                    .getMethod("moveRootTaskToDisplay", int.class, int.class);
            move.invoke(service, rootTaskId, displayId);
            Ln.i("TASKS", "asked the framework to move root " + rootTaskId + " to " + displayId);
            return true;
        } catch (Throwable refused) {
            Ln.i("TASKS", "framework would not move root " + rootTaskId + ": " + refused);
        }
        String[] commands = {
                "am display move-stack " + rootTaskId + " " + displayId,
                "cmd activity display move-root-task " + rootTaskId + " " + displayId,
        };
        for (String command : commands) {
            if (run(command)) {
                Ln.i("TASKS", command);
                return true;
            }
        }
        return false;
    }

    /**
     * The packages with a window on one display, newest last.
     *
     * This is what "open" means on a desktop. Asking whether a process is alive answers a different
     * question and answers it wrongly: Chrome's process outlives its window by design, so closing
     * the window left it sitting in the taskbar with nothing on screen behind it.
     */
    public static java.util.List<String> onDisplay(int displayId, String exclude) {
        java.util.LinkedHashSet<String> packages = new java.util.LinkedHashSet<>();
        for (Object task : rootTasks()) {
            Integer display = number(task, "displayId");
            if (display == null || display != displayId) continue;
            String name = packageOf(task);
            if (name != null && !name.equals(exclude)) packages.add(name);
        }
        return new ArrayList<>(packages);
    }

    private static String packageOf(Object task) {
        for (String name : new String[] { "topActivity", "baseActivity", "realActivity", "origActivity" }) {
            Object value = field(task, name);
            if (value instanceof ComponentName) return ((ComponentName) value).getPackageName();
        }
        Object children = field(task, "childTaskNames");
        if (children instanceof String[] && ((String[]) children).length > 0) {
            String child = ((String[]) children)[0];
            int slash = child == null ? -1 : child.indexOf('/');
            if (slash > 0) return child.substring(0, slash);
        }
        return null;
    }

    /** Every root task the framework will tell us about, on any display. */
    private static List<Object> rootTasks() {
        try {
            Object service = service();
            Class<?> type = Class.forName("android.app.IActivityTaskManager");
            List<Method> candidates = new ArrayList<>();
            for (Method method : type.getMethods()) {
                if (method.getName().startsWith("getAllRootTaskInfos")) candidates.add(method);
            }
            // Fewest arguments first: the no-argument form returns every display at once, which is
            // the question being asked. The per-display forms are a fallback for releases that only
            // have those, and are then asked about the displays that can plausibly hold a window.
            candidates.sort((a, b) -> a.getParameterCount() - b.getParameterCount());
            for (Method method : candidates) {
                if (method.getParameterCount() == 0) {
                    Object result = invoke(service, method);
                    if (result instanceof List) return cast((List<?>) result);
                } else {
                    List<Object> gathered = new ArrayList<>();
                    for (int display = 0; display < 64; display++) {
                        Object result = invoke(service, method, display);
                        if (result instanceof List) gathered.addAll(cast((List<?>) result));
                    }
                    if (!gathered.isEmpty()) return gathered;
                }
            }
        } catch (Throwable failure) {
            Ln.e("TASKS", "could not read the task list", failure);
        }
        return Collections.emptyList();
    }

    private static Object invoke(Object service, Method method, Object... arguments) {
        try {
            method.setAccessible(true);
            return method.invoke(service, arguments);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Object> cast(List<?> source) {
        List<Object> result = new ArrayList<>();
        for (Object item : source) if (item != null) result.add(item);
        return result;
    }

    /** Whether a root task belongs to a package, however the framework happens to say so. */
    private static boolean holds(Object task, String packageName) {
        for (String name : new String[] { "topActivity", "baseActivity", "realActivity", "origActivity" }) {
            Object value = field(task, name);
            if (value instanceof ComponentName
                    && packageName.equals(((ComponentName) value).getPackageName())) {
                return true;
            }
        }
        Object children = field(task, "childTaskNames");
        if (children instanceof String[]) {
            for (String child : (String[]) children) {
                if (child != null && child.startsWith(packageName + "/")) return true;
            }
        }
        return false;
    }

    private static Integer number(Object instance, String name) {
        Object value = field(instance, name);
        return value instanceof Integer ? (Integer) value : null;
    }

    private static Object field(Object instance, String name) {
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (Throwable keepLooking) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object service() throws Exception {
        return Class.forName("android.app.ActivityTaskManager")
                .getDeclaredMethod("getService")
                .invoke(null);
    }

    private static boolean run(String command) {
        try {
            return new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception failure) {
            return false;
        }
    }
}
