package com.minimont.desktop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * A window with nothing in it, which exists to open the file picker and go away again.
 *
 * The wallpaper card is on the desktop, but Android's picker is an activity and belongs on the
 * screen the person is holding. So the card asks, this opens on the phone, and what comes back is a
 * URI the desktop can read for as long as it is the wallpaper.
 */
class WallpaperPickerActivity : ComponentActivity() {

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Persisted, because the desktop will read this again after a reboot and a URI that
            // only works until the picker closes is a wallpaper that lasts one session.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            DesktopStore.setImage(uri.toString())
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DesktopStore.load(this)
        pick.launch(arrayOf("image/*"))
    }
}
