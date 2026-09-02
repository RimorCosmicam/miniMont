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
        BLACK("Black"),
        MUSTARD("Mustard stripes"),
        LIVE("Green stripes"),
        DANGER("Red stripes"),
        IMAGE("A picture")
    }

    data class State(
        val backdrop: Backdrop = Backdrop.BLACK,
        /** The chosen picture, if there is one. Held even while another backdrop is showing. */
        val image: String? = null,
        val pinned: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private lateinit var preferences: android.content.SharedPreferences

    fun load(context: Context) {
        if (::preferences.isInitialized) return
        preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _state.value = State(
            backdrop = runCatching {
                Backdrop.valueOf(preferences.getString(BACKDROP, null) ?: Backdrop.BLACK.name)
            }.getOrDefault(Backdrop.BLACK),
            image = preferences.getString(IMAGE, null),
            pinned = preferences.getString(PINNED, "").orEmpty()
                .split(',').filter { it.isNotBlank() }
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

    private const val FILE = "minimont"
    private const val BACKDROP = "wallpaper"
    private const val IMAGE = "wallpaper_image"
    private const val PINNED = "pinned"
}
