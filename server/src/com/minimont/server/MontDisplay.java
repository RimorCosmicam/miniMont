package com.minimont.server;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * The display miniMont draws its own desktop on.
 *
 * This began as AirMate's `DexDisplay`, whose whole trick was one flag: set
 * `SHOULD_SHOW_SYSTEM_DECORATIONS` and One UI attaches its own SecondaryLauncher and DexTaskbar to
 * any trusted display big enough to hold them, so AirMate never had to draw a desktop at all.
 *
 * miniMont wants the opposite. The flag is cleared, One UI puts nothing here, and what arrives on
 * the empty screen is miniMont's own backdrop, dock and status card. The desktop is ours, and this
 * makes the screen it lives on and then gets out of the way.
 *
 * The display must still be *trusted*, because only a trusted display will host another app's
 * windows, and only uid 2000 may ask for one — which is why this class runs inside a shell process
 * rather than inside the app.
 */
public final class MontDisplay {
    private final VirtualDisplay display;
    private final int displayId;

    private MontDisplay(VirtualDisplay display, int displayId) {
        this.display = display;
        this.displayId = displayId;
    }

    public int id() {
        return displayId;
    }

    /**
     * Build the display and hand it a surface to draw into.
     *
     * `DisplayManager`'s constructor is private and its instance normally comes from a Context that
     * a shell process does not have, so it is built directly. This is the same door scrcpy goes
     * through, and it is a door: `ADD_TRUSTED_DISPLAY` is held by the shell uid only, and Google
     * removed it in Android 15 QPR2 before restoring it in 16 with a note saying it may go again.
     */
    public static MontDisplay create(String name, int width, int height, int dpi, Surface surface)
            throws Exception {
        return create(name, width, height, dpi, surface, 0, false);
    }

    /**
     * A display with nowhere to draw, for windows that are not being looked at.
     *
     * A virtual desktop is a display like any other, of exactly the same size and density as the
     * real one — that is the whole point, because a task moved between displays of different
     * geometry is reconfigured, and an app that relayouts every time you change desktop is an app
     * that loses its place. It simply has no surface, so nothing composites and nothing encodes.
     */
    public static MontDisplay parked(String name, int width, int height, int dpi) throws Exception {
        return create(name, width, height, dpi, null, 0, false);
    }

    /**
     * @param flagOverride the exact flag word to use, or zero to work one out.
     *
     * The override exists because these flags are the only lever we have over what One UI decides to
     * put on the display, and the difference between a desktop with a wallpaper and one without is a
     * single bit somewhere in here. Being able to try a combination without a rebuild turns an
     * afternoon of guessing into a couple of minutes of measuring.
     */
    public static MontDisplay create(String name, int width, int height, int dpi, Surface surface,
                                    int flagOverride, boolean decorations) throws Exception {
        Constructor<DisplayManager> constructor =
                DisplayManager.class.getDeclaredConstructor(android.content.Context.class);
        constructor.setAccessible(true);
        DisplayManager manager = constructor.newInstance(FakeContext.get());

        int flags = flagOverride != 0 ? flagOverride : flags(decorations);
        Ln.i("DEX", "Creating virtual display " + width + "x" + height + "/" + dpi
                + " flags=0x" + Integer.toHexString(flags));
        VirtualDisplay created =
                manager.createVirtualDisplay(name, width, height, dpi, surface, flags);
        if (created == null) throw new IllegalStateException("createVirtualDisplay returned null");
        int id = created.getDisplay().getDisplayId();
        Ln.i("DEX", "Display created, displayId = " + id);
        // What the framework kept, which is not always what was asked for.
        Ln.i("DEX", "Display reports: " + created.getDisplay());
        return new MontDisplay(created, id);
    }

    /**
     * Put the display into freeform windowing.
     *
     * This is what decides whether the apps on the desktop are windows or are each full screen, and
     * a desktop whose every app is full screen is a launcher, not a desktop.
     *
     * Note that the display's own `mWindowingMode` keeps reading `fullscreen` afterwards; the mode
     * that changed is the one new tasks inherit, so it shows up on a launched task and nowhere else.
     */
    public void enableFreeform() {
        String command = "wm set-display-windowing-mode -d " + displayId + " 5";
        Ln.i("DEX", command);
        try {
            Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            int status = process.waitFor();
            if (status != 0) Ln.e("DEX", "freeform request exited " + status, null);
        } catch (Exception error) {
            Ln.e("DEX", "could not set freeform windowing mode", error);
        }
    }

    public void release() {
        try {
            display.release();
        } catch (Exception ignored) {
            // The display goes with the process anyway; a failure here has nothing left to break.
        }
    }

    /**
     * The flags, read off the framework rather than written down.
     *
     * Their numeric values are stable in practice but they are still hidden constants, and a field
     * that has been renamed should cost us that one capability rather than the whole display.
     */
    private static int flags(boolean decorations) {
        int flags = flag("VIRTUAL_DISPLAY_FLAG_PUBLIC", 1 << 0)
                | flag("VIRTUAL_DISPLAY_FLAG_PRESENTATION", 1 << 1)
                | flag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", 1 << 3)
                | flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", 1 << 6)
                | flag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT", 1 << 7)
                | flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", 1 << 8);
        // The one flag miniMont exists to leave off. With it, One UI puts its own launcher, taskbar
        // and wallpaper here and miniMont is a skin over somebody else's desktop. Without it the
        // display comes up empty and stays that way until we put something on it.
        //
        // It is still a switch rather than a deletion, because what else travels with system
        // decorations on this device — the IME above all — has not been measured yet, and finding
        // that out must not need a rebuild.
        if (decorations) flags |= flag("VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS", 1 << 9);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            flags |= flag("VIRTUAL_DISPLAY_FLAG_TRUSTED", 1 << 10)
                    | flag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP", 1 << 11)
                    | flag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED", 1 << 12)
                    | flag("VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED", 1 << 13);
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            flags |= flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", 1 << 14)
                    | flag("VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP", 1 << 15);
        }
        return flags;
    }

    private static int flag(String name, int fallback) {
        try {
            Field field = DisplayManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
