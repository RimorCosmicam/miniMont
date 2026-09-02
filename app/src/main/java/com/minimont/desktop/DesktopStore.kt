package com.minimont.desktop

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
        THIN("Thin", 26, 5, 15, 10),
        REGULAR("Regular", 30, 7, 16, 10),
        THICK("Thick", 34, 9, 18, 11)
    }

    data class State(
        val backdrop: Backdrop = Backdrop.MUSTARD,
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
        val superFill: Boolean = false,
        val thickness: Thickness = Thickness.REGULAR
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private lateinit var preferences: android.content.SharedPreferences

    fun load(context: Context) {
        if (::preferences.isInitialized) return
        preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _state.value = State(
            backdrop = runCatching {
                Backdrop.valueOf(preferences.getString(BACKDROP, null) ?: Backdrop.MUSTARD.name)
            }.getOrDefault(Backdrop.MUSTARD),
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
            }.getOrDefault(Thickness.REGULAR)
        )
    }

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
}
