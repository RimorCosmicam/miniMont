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

    /** The root task holding a package, as {rootTaskId, displayId}, or null if it has none. */
    public static int[] find(String packageName) {
        for (Object task : rootTasks()) {
            if (!holds(task, packageName)) continue;
            Integer id = number(task, "taskId");
            Integer display = number(task, "displayId");
            if (id != null && display != null) return new int[] { id, display };
        }
        return null;
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
