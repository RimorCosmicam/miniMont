package com.minimont.desktop

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What the desktop is, between runs.
 *
 * The wallpaper and the pinned apps are the only two things miniMont remembers, and both are read
 * by the backdrop activity and written by the chrome — two windows on two different surfaces, in
 * one process. So they are held here as flows and mirrored into preferences, rather than each
 * screen reading the file and hoping the other one has finished with it.
 */
object DesktopStore {

    /** The backdrops that need no file: drawn, not loaded. */
    enum class Backdrop(val label: String) {
        /** The one miniMont ships with, and what it looks like before anybody chooses anything. */
        MONT("Mont"),
        MUSTARD("Mustard"),
        BLACK("Black"),
        LIVE("Green"),
        DANGER("Red"),
        IMAGE("A picture")
    }

    /** How the app drawer lays its applications out. */
    enum class Drawer(val label: String) { LIST("List"), GRID("Grid") }

    /**
     * How thick the taskbar is.
     *
     * Three steps, and small ones. The bar's height is set by the tallest thing in it, which is the
     * clock stack and not the icons — so a thickness that only changed the icons would change
     * nothing at all. Each step moves the icons, the padding and the type together, and the type
     * never goes below the sizes Mont already uses for a clock and an explanatory line, because a
     * thinner bar you cannot read the date on is not a thinner bar, it is a broken one.
     */
    enum class Thickness(
        val label: String,
        val icon: Int,
        val padding: Int,
        val time: Int,
        val date: Int
    ) {
        THIN("Thin", 22, 4, 14, 9),
        REGULAR("Regular", 26, 5, 15, 10),
        LARGE("Large", 30, 7, 16, 10)
    }

    /** What can sit on the desktop itself. */
    enum class Kind { APP, WIDGET }

    /**
     * One thing on the desktop, and where it is.
     *
     * Positions are in the display's own dp and are absolute, not a cell in a grid: a desktop where
     * everything snaps to a lattice is a desktop that rearranges itself when the resolution
     * changes, and the whole point of putting something somewhere is that it stays there.
     */
    data class Item(
        val id: String,
        val kind: Kind,
        /** `package/class` for an app, the provider's component for a widget. */
        val component: String,
        val x: Int,
        val y: Int,
        /** Widgets only; an icon is whatever size an icon is. */
        val width: Int = 180,
        val height: Int = 110,
        /** Widgets only: the id the host allocated for this one. */
        val widgetId: Int = 0
    ) {
        fun encode(): String = listOf(id, kind.name, component, x, y, width, height, widgetId)
            .joinToString("\u0001")

        companion object {
            fun decode(line: String): Item? {
                val parts = line.split("\u0001")
                if (parts.size < 8) return null
                return runCatching {
                    Item(
                        id = parts[0],
                        kind = Kind.valueOf(parts[1]),
                        component = parts[2],
                        x = parts[3].toInt(),
                        y = parts[4].toInt(),
                        width = parts[5].toInt(),
                        height = parts[6].toInt(),
                        widgetId = parts[7].toInt()
                    )
                }.getOrNull()
            }
        }
    }

    data class State(
        val backdrop: Backdrop = Backdrop.MONT,
        /** The chosen picture, if there is one. Held even while another backdrop is showing. */
        val image: String? = null,
        val pinned: List<String> = emptyList(),
        val drawer: Drawer = Drawer.LIST,
        /** Grid only: whether a name is written under each icon. */
        val drawerTitles: Boolean = true,
        val drawerColumns: Int = 5,
        /** Sideways in pages, rather than down in one run. */
        val drawerPaged: Boolean = false,
        /**
         * Let the drawer take the whole screen when it has more than fits.
         *
         * Off, a Mont surface is capped and scrolls inside the cap. On, it grows instead — which is
         * the right answer when you are looking for one application among two hundred and the wrong
         * one when you keep six pinned and know where they are.
         */
        /**
         * Fill the whole area above the taskbar with a grid of every application.
         *
         * Not a taller list — a list that reaches the top of the screen is still one column of
         * names with the rest of the display beside it doing nothing. SuperFill is a different
         * layout: the grid takes the fillable area and works out its own columns from the width it
         * was given, which is why the column setting goes quiet while it is on.
         */
        val superFill: Boolean = false,
        val thickness: Thickness = Thickness.REGULAR,
        /** What is on the desktop: shortcuts and widgets, in the order they were put there. */
        val items: List<Item> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private lateinit var preferences: android.content.SharedPreferences

    fun load(context: Context) {
        if (::preferences.isInitialized) return
        preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        migrate()
        // The grid migration runs at the *end* of load, below, once the items it is meant to move
        // have actually been read. Run here it saw an empty list, moved nothing, and wrote itself
        // down as done — which is the whole failure mode of a migration that runs too early.
        _state.value = State(
            backdrop = runCatching {
                Backdrop.valueOf(preferences.getString(BACKDROP, null) ?: Backdrop.MONT.name)
            }.getOrDefault(Backdrop.MONT),
            image = preferences.getString(IMAGE, null),
            pinned = preferences.getString(PINNED, "").orEmpty()
                .split(',').filter { it.isNotBlank() },
            drawer = runCatching {
                Drawer.valueOf(preferences.getString(DRAWER, null) ?: Drawer.LIST.name)
            }.getOrDefault(Drawer.LIST),
            drawerTitles = preferences.getBoolean(DRAWER_TITLES, true),
            drawerColumns = preferences.getInt(DRAWER_COLUMNS, 5),
            drawerPaged = preferences.getBoolean(DRAWER_PAGED, false),
            superFill = preferences.getBoolean(SUPER_FILL, false),
            thickness = runCatching {
                Thickness.valueOf(preferences.getString(THICKNESS, null) ?: Thickness.REGULAR.name)
            }.getOrDefault(Thickness.REGULAR),
            items = preferences.getString(ITEMS, "").orEmpty()
                .split("\n").mapNotNull { Item.decode(it) }
        )
        migrateToGrid(context)
    }

    /**
     * Move anybody still on the old default onto the new one, once.
     *
     * A default only applies until something is written down, and the mustard wash was written down
     * the first time it was the default — so shipping a wallpaper and calling it the default changed
     * nothing for anyone who had already run miniMont. This moves that one value and marks itself
     * done, so a mustard chosen *afterwards* is a choice and stays one.
     */
    private fun migrate() {
        if (!preferences.getBoolean(MIGRATED, false)) {
            preferences.edit().putBoolean(MIGRATED, true).apply()
            if (preferences.getString(BACKDROP, null) == Backdrop.MUSTARD.name) {
                preferences.edit().putString(BACKDROP, Backdrop.MONT.name).apply()
            }
        }
    }

    /**
     * Bring anything placed before the grid existed onto it.
     *
     * Dragging has always snapped a position and never a size, so a widget added when the default
     * was "whatever the provider called its minimum" kept that size for good — 80 by 60 against a
     * cell of 72, sitting at 40,40 with something else on top of it, and no amount of moving it
     * about would ever have fixed either. Once, and written down as done.
     */
    private fun migrateToGrid(context: Context) {
        if (preferences.getBoolean(GRID_MIGRATED, false)) return
        preferences.edit().putBoolean(GRID_MIGRATED, true).apply()

        val taken = mutableSetOf<Pair<Int, Int>>()
        val moved = _state.value.items.map { item ->
            // A widget's size is asked of its provider rather than taken from what was written
            // down. The stored figure is whatever miniMont guessed on the day it was added — and
            // one of those guesses has already been flattened by an earlier pass of this very
            // migration. The provider is the only thing that has always known.
            val wanted = if (item.kind == Kind.WIDGET) providerSize(context, item.widgetId) else null
            val width = round(wanted?.first ?: item.width, floor = if (item.kind == Kind.WIDGET) CELL * 2 else CELL)
            val height = round(wanted?.second ?: item.height, floor = CELL)
            var x = snap(item.x)
            var y = snap(item.y)
            // Every cell a thing covers, not just the one it starts in. Checking corners let a
            // two-cell widget at column nought sit under one starting at column one: neither
            // shared a top-left, and they overlapped down their whole length.
            while (cells(x, y, width, height).any { it in taken }) {
                x += CELL
                if (x > MARGIN + CELL * 11) {
                    x = MARGIN
                    y += CELL
                }
            }
            taken.addAll(cells(x, y, width, height))
            item.copy(x = x, y = y, width = width, height = height)
        }
        if (moved.isEmpty()) return
        _state.update { it.copy(items = moved) }
        writeItems()
    }

    /** Every cell a rectangle stands on. */
    private fun cells(x: Int, y: Int, width: Int, height: Int): List<Pair<Int, Int>> {
        val columns = (width / CELL).coerceAtLeast(1)
        val rows = (height / CELL).coerceAtLeast(1)
        return buildList {
            for (column in 0 until columns) {
                for (row in 0 until rows) add(x + column * CELL to y + row * CELL)
            }
        }
    }

    /** What a widget's provider says it wants, in dp, or null if it will not say. */
    private fun providerSize(context: Context, widgetId: Int): Pair<Int, Int>? = runCatching {
        if (widgetId == 0) return null
        val info = android.appwidget.AppWidgetManager.getInstance(context)
            .getAppWidgetInfo(widgetId) ?: return null
        val density = context.resources.displayMetrics.density
        val cellsWide = if (android.os.Build.VERSION.SDK_INT >= 31) info.targetCellWidth else 0
        val cellsHigh = if (android.os.Build.VERSION.SDK_INT >= 31) info.targetCellHeight else 0
        val width = maxOf((info.minWidth / density).toInt(), cellsWide * CELL)
        val height = maxOf((info.minHeight / density).toInt(), cellsHigh * CELL)
        width to height
    }.getOrNull()

    private fun snap(value: Int): Int =
        MARGIN + (((value - MARGIN).toFloat() / CELL).roundToInt().coerceAtLeast(0)) * CELL

    /**
     * Up to the next whole cell, never down.
     *
     * Rounding to the *nearest* took a hundred-dp widget to seventy-two, which is a widget losing
     * a quarter of its height to a tidy-up. A size can afford to be generous and cannot afford to
     * be short: the thing inside it was already clipping.
     */
    private fun round(value: Int, floor: Int): Int =
        maxOf(floor, ceil(value.toFloat() / CELL).toInt().coerceAtLeast(1) * CELL)

    /** The grid's own figures. A cell is an icon and its air; the margin is an icon and a half. */
    private const val CELL = 72
    private const val MARGIN = 72

    fun setBackdrop(backdrop: Backdrop) {
        _state.update { it.copy(backdrop = backdrop) }
        preferences.edit().putString(BACKDROP, backdrop.name).apply()
    }

    /** A picture chosen on the phone. Choosing one is also choosing to show it. */
    fun setImage(uri: String) {
        _state.update { it.copy(image = uri, backdrop = Backdrop.IMAGE) }
        preferences.edit()
            .putString(IMAGE, uri)
            .putString(BACKDROP, Backdrop.IMAGE.name)
            .apply()
    }

    /**
     * Pin or unpin, which is the only thing that makes a dock different from a task list.
     *
     * An app that is pinned stays in the dock when it is not running, at 58% — the same rule
     * everything else in Mont follows, applied to somebody else's artwork.
     */
    fun togglePin(packageName: String) {
        _state.update { current ->
            val pinned =
                if (packageName in current.pinned) current.pinned - packageName
                else current.pinned + packageName
            preferences.edit().putString(PINNED, pinned.joinToString(",")).apply()
            current.copy(pinned = pinned)
        }
    }

    fun setDrawer(drawer: Drawer) {
        _state.update { it.copy(drawer = drawer) }
        preferences.edit().putString(DRAWER, drawer.name).apply()
    }

    fun setDrawerTitles(on: Boolean) {
        _state.update { it.copy(drawerTitles = on) }
        preferences.edit().putBoolean(DRAWER_TITLES, on).apply()
    }

    fun setDrawerColumns(columns: Int) {
        _state.update { it.copy(drawerColumns = columns) }
        preferences.edit().putInt(DRAWER_COLUMNS, columns).apply()
    }

    fun setDrawerPaged(on: Boolean) {
        _state.update { it.copy(drawerPaged = on) }
        preferences.edit().putBoolean(DRAWER_PAGED, on).apply()
    }

    fun setSuperFill(on: Boolean) {
        _state.update { it.copy(superFill = on) }
        preferences.edit().putBoolean(SUPER_FILL, on).apply()
    }

    fun setThickness(thickness: Thickness) {
        _state.update { it.copy(thickness = thickness) }
        preferences.edit().putString(THICKNESS, thickness.name).apply()
    }

    /**
     * The next free cell, so two things added one after the other do not land on top of each other.
     *
     * The grid's own figures, duplicated here rather than reached for: this is asked from the
     * settings card, which is drawn in a different window from the desktop and has no business
     * knowing how the desktop lays itself out beyond where the next thing goes.
     */
    fun nextCell(): Pair<Int, Int> {
        val occupied = _state.value.items
            .flatMap { cells(it.x, it.y, it.width, it.height) }
            .toSet()
        for (row in 0 until 12) {
            for (column in 0 until 12) {
                val x = MARGIN + column * CELL
                val y = MARGIN + row * CELL
                if ((x to y) !in occupied) return x to y
            }
        }
        return MARGIN to MARGIN
    }

    /** Put something on the desktop, where it was dropped. */
    fun addItem(item: Item) {
        _state.update { it.copy(items = it.items + item) }
        writeItems()
    }

    fun moveItem(id: String, x: Int, y: Int) {
        _state.update { current ->
            current.copy(items = current.items.map { if (it.id == id) it.copy(x = x, y = y) else it })
        }
        writeItems()
    }

    fun resizeItem(id: String, width: Int, height: Int) {
        _state.update { current ->
            current.copy(
                items = current.items.map {
                    if (it.id == id) it.copy(width = width, height = height) else it
                }
            )
        }
        writeItems()
    }

    fun removeItem(id: String) {
        _state.update { current -> current.copy(items = current.items.filterNot { it.id == id }) }
        writeItems()
    }

    private fun writeItems() {
        preferences.edit()
            .putString(ITEMS, _state.value.items.joinToString("\n") { it.encode() })
            .apply()
    }

    private const val FILE = "minimont"
    private const val BACKDROP = "wallpaper"
    private const val IMAGE = "wallpaper_image"
    private const val PINNED = "pinned"
    private const val DRAWER = "drawer"
    private const val DRAWER_TITLES = "drawer_titles"
    private const val DRAWER_COLUMNS = "drawer_columns"
    private const val DRAWER_PAGED = "drawer_paged"
    private const val SUPER_FILL = "super_fill"
    private const val THICKNESS = "thickness"
    private const val MIGRATED = "mont_wallpaper_migrated"
    private const val ITEMS = "desktop_items"
    private const val GRID_MIGRATED = "desktop_on_grid_4"
}
