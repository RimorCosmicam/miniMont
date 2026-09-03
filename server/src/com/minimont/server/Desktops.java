package com.minimont.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Virtual desktops, built out of displays nobody is looking at.
 *
 * A desktop is a set of windows, and Android already has a thing that holds a set of windows: a
 * display. So every desktop gets one — identical in size and density to the real one, and with no
 * surface, so nothing composites and nothing encodes. Only the current desktop's windows live on
 * the display being streamed; the rest sit on theirs, running, out of sight.
 *
 * Identical geometry is the whole reason this works. A task moved between displays of different
 * size is reconfigured, and an app that relayouts every time you change desktop is an app that
 * loses its scroll position, its selection and its place. Moved between displays of the same shape,
 * it does not notice.
 */
public final class Desktops {

    private final int width;
    private final int height;
    private final int dpi;
    private final String ours;

    /** Where each desktop's windows wait while it is not the one on screen. */
    private final List<MontDisplay> storage = new ArrayList<>();

    private int visibleDisplayId = -1;
    private int current;

    public Desktops(int width, int height, int dpi, String ours) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.ours = ours;
        storage.add(null);
    }

    /** Told which display is the one being watched, every time a session starts. */
    public void onDisplay(int displayId) {
        visibleDisplayId = displayId;
        current = 0;
    }

    public int count() {
        return storage.size();
    }

    public int current() {
        return current;
    }

    /** A new, empty desktop. Its display is made when something first needs to be parked on it. */
    public int add() {
        storage.add(null);
        Ln.i("DESKS", "added desktop " + (storage.size() - 1));
        return storage.size() - 1;
    }

    /**
     * Take a desktop away, and its windows with it — onto the first desktop, not into nothing.
     *
     * Closing what is on a desktop when you remove it would be a reasonable thing for a desktop
     * manager to do and a terrible thing to discover.
     */
    public void remove(int index) {
        if (index <= 0 || index >= storage.size()) return;
        if (current == index) switchTo(0);
        MontDisplay display = storage.get(index);
        if (display != null) {
            for (int[] task : Tasks.tasksOn(display.id(), ours)) {
                Tasks.moveToDisplay(task[0], visibleDisplayId);
            }
            display.release();
        }
        storage.remove(index);
        if (current > index) current--;
        Ln.i("DESKS", "removed desktop " + index);
    }

    /** Park what is on screen, and bring back what belongs to the desktop being asked for. */
    public void switchTo(int index) {
        if (visibleDisplayId < 0 || index == current || index < 0 || index >= storage.size()) return;

        int parked = storageFor(current);
        if (parked >= 0) {
            for (int[] task : Tasks.tasksOn(visibleDisplayId, ours)) {
                Tasks.moveToDisplay(task[0], parked);
            }
        }

        MontDisplay incoming = storage.get(index);
        if (incoming != null) {
            for (int[] task : Tasks.tasksOn(incoming.id(), ours)) {
                Tasks.moveToDisplay(task[0], visibleDisplayId);
            }
        }

        current = index;
        Ln.i("DESKS", "showing desktop " + index);
    }

    /** What is on each desktop, as `pkg,pkg|pkg||pkg` — one field per desktop, in order. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < storage.size(); index++) {
            if (index > 0) out.append('|');
            List<String> packages;
            if (index == current) {
                packages = Tasks.packagesOn(visibleDisplayId, ours);
            } else {
                MontDisplay display = storage.get(index);
                packages = display == null
                        ? new ArrayList<>()
                        : Tasks.packagesOn(display.id(), ours);
            }
            out.append(String.join(",", packages));
        }
        return out.toString();
    }

    public void release() {
        for (MontDisplay display : storage) {
            if (display != null) display.release();
        }
        storage.clear();
        storage.add(null);
        current = 0;
        visibleDisplayId = -1;
    }

    /** The display a desktop's windows wait on, made the first time it is needed. */
    private int storageFor(int index) {
        if (index < 0 || index >= storage.size()) return -1;
        MontDisplay display = storage.get(index);
        if (display != null) return display.id();
        try {
            MontDisplay made = MontDisplay.parked("miniMont desk " + index, width, height, dpi);
            storage.set(index, made);
            return made.id();
        } catch (Throwable failure) {
            Ln.e("DESKS", "could not make a display for desktop " + index, failure);
            return -1;
        }
    }
}
