package com.minimont.desktop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** One installed application, as the dock and the start menu need it. */
data class DesktopApp(
    val label: String,
    val packageName: String,
    /** `package/class`, ready for `am start -n`. */
    val component: String,
    val icon: ImageBitmap?
)

/**
 * Everything on the phone that can be opened.
 *
 * Read once and kept, because a start menu that queries the package manager on every keystroke is a
 * start menu that stutters, and the set of installed applications does not change while somebody is
 * looking at a list of it.
 */
object AppCatalog {

    @Volatile
    private var cached: List<DesktopApp>? = null

    fun apps(context: Context): List<DesktopApp> = cached ?: load(context).also { cached = it }

    fun forget() {
        cached = null
    }

    fun byPackage(context: Context, packageName: String): DesktopApp? =
        apps(context).firstOrNull { it.packageName == packageName }

    private fun load(context: Context): List<DesktopApp> {
        val manager = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(query, PackageManager.MATCH_ALL)
            // miniMont's own backdrop is on the display already; it is not an app you open.
            .filter { it.activityInfo.packageName != context.packageName }
            .map { resolved ->
                val info = resolved.activityInfo
                DesktopApp(
                    label = resolved.loadLabel(manager).toString(),
                    packageName = info.packageName,
                    component = "${info.packageName}/${info.name}",
                    icon = runCatching { resolved.loadIcon(manager).toImageBitmap() }.getOrNull()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * An icon, at a size worth drawing on a desktop.
     *
     * Adaptive icons arrive as drawables with no bitmap behind them, so they are rasterised here
     * once rather than every time the dock recomposes.
     */
    private fun Drawable.toImageBitmap(): ImageBitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap.asImageBitmap()
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bitmap.asImageBitmap()
    }
}
