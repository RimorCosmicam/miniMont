package com.minimont.server;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * A mouse and a keyboard on the miniMont display.
 *
 * This is the piece MiniDex never had, and the reason its cursor could move over DeX and not open
 * anything. A UHID mouse belongs to the *system*, not to a display: the kernel hands the framework a
 * report and the framework gives it to whichever display owns the pointer, which is never ours. What
 * was left on screen there was a drawn cursor and a synthesised tap at coordinates, and a launcher
 * icon has no reason to answer a tap that arrived with no hover before it and no button state on it.
 *
 * So miniMont does not simulate a mouse, it injects one. Events carry `SOURCE_MOUSE`,
 * `TOOL_TYPE_MOUSE`, a real button state, and the display id — and, crucially, a **hover stream**
 * while no button is down. Hover is what makes the framework treat this as a pointer rather than as
 * a stab at a coordinate: it draws its own cursor for the display, windows take focus under it, and
 * a press that follows arrives as a click on whatever the pointer was over.
 *
 * The position is kept here rather than asked for, because relative movement is what a touchpad
 * produces and the framework will not tell us where its cursor currently is.
 */
public final class Pointer {

    private static final int INJECT_ASYNC = 2;

    private final int displayId;
    private final int width;
    private final int height;

    private float x;
    private float y;
    private int buttons;
    private long downTime;

    private Object inputManager;
    private Method inject;
    private Method setDisplayIdOnMotion;
    private Method setActionButton;
    private Method setDisplayIdOnKey;

    private final MotionEvent.PointerProperties[] properties = {
            new MotionEvent.PointerProperties()
    };
    private final MotionEvent.PointerCoords[] coordinates = {
            new MotionEvent.PointerCoords()
    };

    public Pointer(int displayId, int width, int height) {
        this.displayId = displayId;
        this.width = width;
        this.height = height;
        // Starts in the middle rather than at the origin: a cursor that appears in the top-left
        // corner looks like a cursor that failed, and the first thing anyone does is move it.
        this.x = width / 2f;
        this.y = height / 2f;
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        coordinates[0].pressure = 1f;
        coordinates[0].size = 1f;
        bind();
    }

    /**
     * The hidden doors, opened once.
     *
     * Every one of these is @hide and every one of them has moved at least once across releases, so
     * they are looked up by name and the failure is reported here rather than surfacing later as a
     * pointer that silently does nothing.
     */
    private void bind() {
        try {
            Class<?> manager;
            Object instance;
            try {
                // Android 14 and later keep the injector on InputManagerGlobal.
                manager = Class.forName("android.hardware.input.InputManagerGlobal");
                instance = manager.getMethod("getInstance").invoke(null);
            } catch (Throwable notThere) {
                manager = Class.forName("android.hardware.input.InputManager");
                instance = manager.getMethod("getInstance").invoke(null);
            }
            Method injector = null;
            for (Method method : manager.getMethods()) {
                if (method.getName().equals("injectInputEvent") && method.getParameterCount() == 2) {
                    injector = method;
                    break;
                }
            }
            if (injector == null) throw new NoSuchMethodException("injectInputEvent");

            inputManager = instance;
            inject = injector;
            setDisplayIdOnMotion = MotionEvent.class.getMethod("setDisplayId", int.class);
            setActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
            setDisplayIdOnKey = KeyEvent.class.getMethod("setDisplayId", int.class);
            Ln.i("POINTER", "injector bound: " + manager.getSimpleName());
        } catch (Throwable failure) {
            Ln.e("POINTER", "no injector; the pointer will not work", failure);
        }
    }

    public boolean ready() {
        return inject != null;
    }

    /** Where the cursor is, so the desktop can be asked and does not have to guess. */
    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    /**
     * Relative movement, as a touchpad produces it.
     *
     * While nothing is pressed this is a hover; while a button is down it is a drag, which is the
     * same distinction a real mouse makes and the reason a window can be pulled by its title.
     */
    public void move(float dx, float dy) {
        x = clamp(x + dx, width);
        y = clamp(y + dy, height);
        motion(buttons == 0 ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_MOVE, buttons, 0);
    }

    /** Put the cursor somewhere absolute. For the tablet, whose touches arrive as positions. */
    public void moveTo(float toX, float toY) {
        x = clamp(toX, width);
        y = clamp(toY, height);
        motion(buttons == 0 ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_MOVE, buttons, 0);
    }

    /**
     * A button, held for exactly as long as the finger is.
     *
     * Press and release are separate calls rather than a click, because a desktop is mostly drags —
     * a title bar moved, a window edge pulled, a selection made — and a click is the special case
     * where the two happen close together.
     */
    public void button(int button, boolean down) {
        int mask = button == 2 ? MotionEvent.BUTTON_SECONDARY
                : button == 3 ? MotionEvent.BUTTON_TERTIARY
                : MotionEvent.BUTTON_PRIMARY;
        if (down) {
            if (buttons == 0) downTime = SystemClock.uptimeMillis();
            buttons |= mask;
            motion(MotionEvent.ACTION_DOWN, buttons, 0);
            motion(MotionEvent.ACTION_BUTTON_PRESS, buttons, mask);
        } else {
            buttons &= ~mask;
            motion(MotionEvent.ACTION_BUTTON_RELEASE, buttons, mask);
            motion(MotionEvent.ACTION_UP, buttons, 0);
            // Forgotten once nothing is held.
            //
            // It was kept, so every event after the first click carried the downTime of that first
            // click — minutes old by the second one. A gesture whose down began several minutes ago
            // is not a click to anything that measures one, which is why clicks worked and then
            // quietly stopped.
            if (buttons == 0) downTime = 0;
            // Back to hovering, so the thing under the cursor lights up again the moment it is
            // released rather than the next time the finger moves.
            motion(MotionEvent.ACTION_HOVER_MOVE, 0, 0);
        }
    }

    /** The wheel, in notches. Positive vertical scrolls the content down, as a wheel does. */
    public void scroll(float horizontal, float vertical) {
        coordinates[0].setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal);
        coordinates[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vertical);
        motion(MotionEvent.ACTION_SCROLL, buttons, 0);
        coordinates[0].setAxisValue(MotionEvent.AXIS_HSCROLL, 0f);
        coordinates[0].setAxisValue(MotionEvent.AXIS_VSCROLL, 0f);
    }

    public void key(int keyCode, int metaState, boolean down) {
        if (inject == null) return;
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(
                now, now,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        try {
            setDisplayIdOnKey.invoke(event, displayId);
            inject.invoke(inputManager, event, INJECT_ASYNC);
        } catch (Throwable failure) {
            Ln.e("POINTER", "key " + keyCode, failure);
        }
    }

    public void tap(int keyCode, int metaState) {
        key(keyCode, metaState, true);
        key(keyCode, metaState, false);
    }

    /**
     * Type a string.
     *
     * The character map turns text into the key events a physical keyboard would have produced,
     * which is what an app on the other end is expecting. Characters the map cannot produce are
     * dropped rather than approximated — a keyboard that types the wrong letter is worse than one
     * that types none.
     */
    public void type(String text) {
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        KeyEvent[] events = map.getEvents(text.toCharArray());
        if (events == null) return;
        for (KeyEvent event : events) {
            KeyEvent aimed = KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), 0);
            try {
                setDisplayIdOnKey.invoke(aimed, displayId);
                inject.invoke(inputManager, aimed, INJECT_ASYNC);
            } catch (Throwable failure) {
                Ln.e("POINTER", "type", failure);
                return;
            }
        }
    }

    private void motion(int action, int buttonState, int actionButton) {
        if (inject == null) return;
        coordinates[0].x = x;
        coordinates[0].y = y;
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(
                downTime == 0 ? now : downTime, now, action,
                1, properties, coordinates,
                0, buttonState, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        try {
            setDisplayIdOnMotion.invoke(event, displayId);
            if (actionButton != 0) setActionButton.invoke(event, actionButton);
            inject.invoke(inputManager, event, INJECT_ASYNC);
        } catch (Throwable failure) {
            Ln.e("POINTER", "motion " + action, failure);
        } finally {
            event.recycle();
        }
    }

    private static float clamp(float value, int limit) {
        return value < 0 ? 0 : (value > limit - 1 ? limit - 1 : value);
    }
}
