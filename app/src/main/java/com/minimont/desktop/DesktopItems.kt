@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.minimont.desktop

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.minimont.DesktopController
import com.minimont.ui.mont.MontLabel
import com.minimont.ui.mont.MontRow
import com.minimont.ui.mont.MontSurface
import com.minimont.ui.mont.MontWhite

/** How big a desktop icon is, and how much room its name gets under it. */
private const val ICON = 48
private const val LABEL = 92

/**
 * Everything sitting on the desktop: shortcuts and widgets.
 *
 * Drawn into the backdrop, which is the bottom of the window stack — so these live *under* every
 * application window, which is what a desktop is. Nothing here floats above anything.
 *
 * Positions are absolute and in dp. Dragging writes the new one down as soon as the finger lifts,
 * not on every frame: a desktop that saves sixty times a second while you move an icon is a desktop
 * writing to storage sixty times a second.
 */
@Composable
fun DesktopItems(
    items: List<DesktopStore.Item>,
    host: AppWidgetHost?,
    onOpen: (String) -> Unit
) {
    val density = LocalDensity.current
    var menuFor by remember { mutableStateOf<DesktopStore.Item?>(null) }

    var deskMenu by remember { mutableStateOf<Offset?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            // The desktop itself answers a right click. A long press on the wallpaper does the
            // same, for the tablet, which has fingers and no second button.
            .secondary { at -> deskMenu = at }
            // Taps handled without `clickable`, which draws an indication — a ripple across the
            // whole wallpaper every time anybody put the pointer down on it. The desktop has
            // nothing to say about being touched; it only needs to know a tap happened so it can
            // put its menu away.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { deskMenu = null },
                    onLongPress = { at -> deskMenu = at }
                )
            }
    ) {
        items.forEach { item ->
            var dragX by remember(item.id) { mutableStateOf(item.x.toFloat()) }
            var dragY by remember(item.id) { mutableStateOf(item.y.toFloat()) }

            Box(
                Modifier
                    .offset { IntOffset(with(density) { dragX.dp.roundToPx() }, with(density) { dragY.dp.roundToPx() }) }
                    .pointerInput(item.id) {
                        detectDragGestures(
                            onDrag = { change, dragged ->
                                change.consume()
                                dragX += with(density) { dragged.x.toDp().value }
                                dragY += with(density) { dragged.y.toDp().value }
                            },
                            // Written down once the finger lifts. Sixty writes a second while
                            // somebody moves an icon is sixty writes a second to storage.
                            onDragEnd = {
                                DesktopStore.moveItem(item.id, dragX.toInt(), dragY.toInt())
                            }
                        )
                    }
            ) {
                when (item.kind) {
                    DesktopStore.Kind.APP -> Shortcut(
                        item = item,
                        onOpen = { onOpen(item.component) },
                        onHold = { menuFor = item }
                    )

                    DesktopStore.Kind.WIDGET -> Widget(
                        item = item,
                        host = host,
                        onHold = { menuFor = item }
                    )
                }
            }
        }

        deskMenu?.let { at ->
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            with(density) { at.x.toDp().roundToPx() },
                            with(density) { at.y.toDp().roundToPx() }
                        )
                    }
                    .background(MontSurface)
                    .width(220.dp)
                    .padding(start = 14.dp, top = 8.dp, end = 10.dp, bottom = 8.dp)
            ) {
                Column {
                    // The cards these open live in the chrome window, above every application, so
                    // the backdrop asks for one rather than drawing it down here under everything.
                    MontRow(label = "Add a widget") {
                        DesktopRequests.ask(DesktopRequests.Panel.WIDGETS)
                        deskMenu = null
                    }
                    MontRow(label = "Change the wallpaper") {
                        DesktopRequests.ask(DesktopRequests.Panel.WALLPAPER)
                        deskMenu = null
                    }
                    MontRow(label = "Settings") {
                        DesktopRequests.ask(DesktopRequests.Panel.SETTINGS)
                        deskMenu = null
                    }
                    MontRow(label = "Cancel", active = false) { deskMenu = null }
                }
            }
        }

        menuFor?.let { item ->
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            with(density) { item.x.dp.roundToPx() },
                            with(density) { (item.y + ICON + 12).dp.roundToPx() }
                        )
                    }
                    .background(MontSurface)
                    .width(200.dp)
            ) {
                Column(Modifier.padding(start = 14.dp, top = 8.dp, end = 10.dp, bottom = 8.dp)) {
                    MontRow(label = "Remove from the desktop") {
                        if (item.kind == DesktopStore.Kind.WIDGET && item.widgetId != 0) {
                            runCatching { host?.deleteAppWidgetId(item.widgetId) }
                        }
                        DesktopStore.removeItem(item.id)
                        menuFor = null
                    }
                    MontRow(label = "Cancel", active = false) { menuFor = null }
                }
            }
        }
    }
}

@Composable
private fun Shortcut(item: DesktopStore.Item, onOpen: () -> Unit, onHold: () -> Unit) {
    val context = LocalContext.current
    val app = remember(item.component) {
        AppCatalog.byPackage(context, item.component.substringBefore('/'))
    }

    Column(
        Modifier
            .width(LABEL.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onHold),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val icon = app?.icon
        if (icon != null) {
            Image(icon, contentDescription = app.label, modifier = Modifier.size(ICON.dp))
        } else {
            Box(Modifier.size(ICON.dp).background(Color.White.copy(alpha = MontWhite.TRACK)))
        }
        Spacer(Modifier.height(4.dp))
        // Named in white on whatever the wallpaper happens to be, which is the one place in
        // miniMont where type sits on a picture rather than on Mont's black.
        MontLabel(app?.label.orEmpty(), size = 11, alpha = MontWhite.PRIMARY)
    }
}

/**
 * A widget, hosted.
 *
 * The provider draws it; miniMont gives it a rectangle and stays out of it. Held in an AndroidView
 * because an app widget is a RemoteViews hierarchy inflated by the framework, and there is no
 * Compose equivalent of that and never will be.
 */
@Composable
private fun Widget(item: DesktopStore.Item, host: AppWidgetHost?, onHold: () -> Unit) {
    val context = LocalContext.current

    Box(
        Modifier
            .size(item.width.dp, item.height.dp)
            .combinedClickable(onClick = {}, onLongClick = onHold)
    ) {
        if (host == null || item.widgetId == 0) {
            Box(Modifier.fillMaxSize().background(MontSurface)) {
                MontLabel("WIDGET", Modifier.align(Alignment.Center), alpha = MontWhite.DIM, size = 11)
            }
            return@Box
        }
        AndroidView(
            factory = { viewContext ->
                val manager = AppWidgetManager.getInstance(viewContext)
                val info = manager.getAppWidgetInfo(item.widgetId)
                (host.createView(viewContext, item.widgetId, info) as AppWidgetHostView).apply {
                    // A host view arrives with its provider's own padding on it, which on a
                    // desktop is somebody else's margin inside our rectangle.
                    setPadding(0, 0, 0, 0)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // Told in the units it asks for, and told the *one* size it has rather than a
                // range. A widget given a range picks a layout for the smallest of it, which is
                // why they were all arriving in their most cramped form whatever room they had.
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    view.updateAppWidgetSize(
                        android.os.Bundle.EMPTY,
                        listOf(android.util.SizeF(item.width.toFloat(), item.height.toFloat()))
                    )
                } else {
                    @Suppress("DEPRECATION")
                    view.updateAppWidgetSize(null, item.width, item.height, item.width, item.height)
                }
            }
        )
    }
}

/**
 * Binding a widget without asking, because the shell already said yes.
 *
 * Normally an app needs the user to answer a system dialog for every widget it binds, or hold a
 * signature permission it cannot be granted. miniMont has a third way: `appwidget grant` is a shell
 * command, and miniMont is holding a shell. The grant happens once, at the host's start.
 */
object Widgets {
    const val HOST_ID = 0x4D4F

    fun providers(context: Context): List<AppWidgetProviderEntry> = runCatching {
        val density = context.resources.displayMetrics.density
        AppWidgetManager.getInstance(context).installedProviders.map { info ->
            AppWidgetProviderEntry(
                label = info.loadLabel(context.packageManager),
                app = runCatching {
                    val application = context.packageManager
                        .getApplicationInfo(info.provider.packageName, 0)
                    context.packageManager.getApplicationLabel(application).toString()
                }.getOrDefault(info.provider.packageName),
                provider = info.provider,
                width = (info.minWidth / density).toInt().coerceIn(80, 480),
                height = (info.minHeight / density).toInt().coerceIn(60, 480),
                // What the provider drew of itself. Falling back to its icon rather than to
                // nothing: a picker of blank rectangles is a list with worse spacing.
                preview = runCatching {
                    (info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0))?.toBitmap()
                }.getOrNull()
            )
        }.sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    private fun android.graphics.drawable.Drawable.toBitmap(): ImageBitmap {
        if (this is android.graphics.drawable.BitmapDrawable && bitmap != null) {
            return bitmap.asImageBitmap()
        }
        val width = intrinsicWidth.coerceIn(1, 720)
        val height = intrinsicHeight.coerceIn(1, 720)
        val bitmap = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888
        )
        setBounds(0, 0, width, height)
        draw(android.graphics.Canvas(bitmap))
        return bitmap.asImageBitmap()
    }

    /**
     * Allocate an id and bind it to a provider.
     *
     * Returns zero when the bind is refused, which means the shell grant has not happened yet —
     * the id is handed straight back rather than left allocated to nothing.
     */
    fun bind(context: Context, host: AppWidgetHost, provider: ComponentName): Int {
        val manager = AppWidgetManager.getInstance(context)
        val id = host.allocateAppWidgetId()
        val bound = runCatching { manager.bindAppWidgetIdIfAllowed(id, provider) }.getOrDefault(false)
        if (!bound) {
            runCatching { host.deleteAppWidgetId(id) }
            return 0
        }
        return id
    }
}

data class AppWidgetProviderEntry(
    val label: String,
    /** Which application it came from, since a provider's own label rarely says. */
    val app: String,
    val provider: ComponentName,
    val width: Int,
    val height: Int,
    val preview: ImageBitmap?
)
