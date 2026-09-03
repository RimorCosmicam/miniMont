@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.minimont.desktop

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import com.minimont.DesktopController
import com.minimont.ui.mont.MontLabel
import com.minimont.ui.mont.MontRow
import com.minimont.ui.mont.MontSurface
import com.minimont.ui.mont.MontWhite

/** How big a desktop icon is. What holds it is the cell, and the cell is the grid's business. */
private const val ICON = 48

/**
 * The desktop's grid, the way a launcher has one.
 *
 * A cell is an icon and the air around it. The margin is an icon and a half from the left, the
 * right and the top — which works out to exactly one cell, so the first column starts one cell in
 * and everything lines up with everything else without a second number being involved.
 *
 * Positions are still stored in dp rather than as row and column. The grid is where things land,
 * not what they are: a desktop that stores coordinates in cells forgets where anything was the
 * moment the resolution changes, and this way a wrong cell size costs alignment rather than layout.
 */
private object Grid {
    /**
     * A cell is wider than it is tall — or rather, taller than it is wide.
     *
     * Launcher cells are not square and never have been: a cell holds an icon *and* its name, and a
     * widget declaring four by one expects a box four cells wide and one cell tall, where that cell
     * is around a hundred dp. Given a square cell it gets something far shorter than it was drawn
     * for, and squashes itself into it — which is what "distorted" was.
     */
    const val WIDE = 72
    const val TALL = 96
    const val MARGIN = (ICON * 3) / 2

    fun snapX(value: Float, limit: Int): Int = snap(value, WIDE, limit)

    fun snapY(value: Float, limit: Int): Int = snap(value, TALL, limit)

    private fun snap(value: Float, step: Int, limit: Int): Int {
        val cells = ((value - MARGIN) / step).roundToInt().coerceAtLeast(0)
        return (MARGIN + cells * step).coerceIn(MARGIN, maxOf(MARGIN, limit))
    }

    /** The first cell nothing is standing in, so two things added in a row do not land on top. */
    fun free(taken: List<DesktopStore.Item>, width: Int, height: Int): Pair<Int, Int> {
        val columns = ((width - MARGIN * 2) / WIDE).coerceAtLeast(1)
        val rows = ((height - MARGIN * 2) / TALL).coerceAtLeast(1)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = MARGIN + column * WIDE
                val y = MARGIN + row * TALL
                if (taken.none { it.x == x && it.y == y }) return x to y
            }
        }
        return MARGIN to MARGIN
    }
}

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
    val configuration = LocalConfiguration.current
    val screen = configuration.screenWidthDp to configuration.screenHeightDp
    var menuFor by remember { mutableStateOf<DesktopStore.Item?>(null) }
    // Which item is in move mode. A widget is a live thing with its own buttons, so dragging it by
    // touching it means every attempt to move one is also an attempt to press what is under the
    // finger — which is why moving is asked for first and only then does a drag mean anything.
    var moving by remember { mutableStateOf<String?>(null) }

    var deskMenu by remember { mutableStateOf<Offset?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            // The desktop itself answers a right click. A long press on the wallpaper does the
            // same, for the tablet, which has fingers and no second button.
            // One handler doing both jobs.
            //
            // Two of them fought: a secondary watcher and a tap detector, on the same node. The tap
            // detector consumed the press first and the right click never arrived. They are the
            // same question anyway — what kind of press was that — so it is asked once.
            //
            // Taken on Main, which is delivered child first, so a widget standing on the desktop
            // has already answered and consumed before this is reached. And no `clickable`
            // anywhere: clickable draws an indication, which was a ripple across the whole
            // wallpaper every time the pointer went down.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type != PointerEventType.Press) continue
                        if (event.changes.any { it.isConsumed }) continue

                        val at = event.changes.firstOrNull()?.position ?: Offset.Zero
                        // Any press on the wallpaper puts away a widget's menu, whichever button
                        // it was: a menu you opened on one thing should not survive you reaching
                        // for another.
                        menuFor = null
                        if (event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            deskMenu = at
                        } else {
                            // A press on bare wallpaper puts away whatever was open — the
                            // desktop's own menu, and any card the chrome has up. Clicking
                            // somewhere else is how anybody dismisses a menu.
                            deskMenu = null
                            DesktopRequests.dismiss()
                        }
                    }
                }
            }
    ) {
        // Keyed by identity, not by position in the list.
        //
        // Without this, removing one item made the next one take its slot in the composition — and
        // an AndroidView's factory runs once, so the surviving widget inherited the host view of
        // the deleted one, bound to a widget id that no longer existed. It stopped drawing, which
        // looked exactly like removing one had removed two.
        items.forEach { item -> key(item.id) {
            var dragX by remember(item.id) { mutableStateOf(item.x.toFloat()) }
            var dragY by remember(item.id) { mutableStateOf(item.y.toFloat()) }

            val draggable = item.kind == DesktopStore.Kind.APP || moving == item.id

            Box(
                Modifier
                    .offset { IntOffset(with(density) { dragX.dp.roundToPx() }, with(density) { dragY.dp.roundToPx() }) }
                    .pointerInput(item.id, draggable) {
                        if (!draggable) return@pointerInput
                        detectDragGestures(
                            onDrag = { change, dragged ->
                                change.consume()
                                dragX += with(density) { dragged.x.toDp().value }
                                dragY += with(density) { dragged.y.toDp().value }
                            },
                            // Written down once the finger lifts. Sixty writes a second while
                            // somebody moves an icon is sixty writes a second to storage.
                            // Snapped when the finger lifts rather than while it moves: an icon
                            // that jumps between cells under the pointer is an icon fighting you.
                            onDragEnd = {
                                dragX = Grid.snapX(dragX, screen.first - Grid.WIDE).toFloat()
                                dragY = Grid.snapY(dragY, screen.second - Grid.TALL).toFloat()
                                DesktopStore.moveItem(item.id, dragX.toInt(), dragY.toInt())
                                moving = null
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
                        moving = moving == item.id,
                        onHold = { menuFor = item }
                    )
                }
            }
        } }

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
            ItemMenu(
                item = item,
                density = density,
                onMove = {
                    moving = item.id
                    menuFor = null
                },
                onResize = { width, height ->
                    DesktopStore.resizeItem(item.id, width, height)
                    menuFor = null
                },
                onRemove = {
                    if (item.kind == DesktopStore.Kind.WIDGET && item.widgetId != 0) {
                        runCatching { host?.deleteAppWidgetId(item.widgetId) }
                    }
                    DesktopStore.removeItem(item.id)
                    menuFor = null
                },
                onDismiss = { menuFor = null }
            )
        }
    }
}

/**
 * What can be done to something on the desktop.
 *
 * Resize offers the sizes the widget itself says it will take, in cells, rather than a free drag —
 * a provider that declares four by two and no more will letterbox itself into anything else, and
 * offering a size it refuses is offering a broken widget.
 */
@Composable
private fun ItemMenu(
    item: DesktopStore.Item,
    density: androidx.compose.ui.unit.Density,
    onMove: () -> Unit,
    onResize: (Int, Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var choosing by remember(item.id) { mutableStateOf(false) }
    val options: List<Pair<Int, Int>> = remember(item.id, choosing) {
        if (choosing) Widgets.sizes(context, item.widgetId) else emptyList()
    }

    Box(
        Modifier
            .offset {
                IntOffset(
                    with(density) { item.x.dp.roundToPx() },
                    with(density) { (item.y + item.height + 6).dp.roundToPx() }
                )
            }
            .background(MontSurface)
            .width(220.dp)
            .padding(start = 14.dp, top = 8.dp, end = 10.dp, bottom = 8.dp)
    ) {
        Column {
            if (choosing) {
                MontLabel("SIZE", size = 11, alpha = MontWhite.DETAIL)
                Spacer(Modifier.height(4.dp))
                if (options.isEmpty()) MontLabel("One size only", size = 12, alpha = MontWhite.DIM)
                options.forEach { (columns, rows) ->
                    val width = columns * Grid.WIDE
                    val height = rows * Grid.TALL
                    MontRow(
                        label = "$columns × $rows",
                        active = width == item.width && height == item.height
                    ) { onResize(width, height) }
                }
                Spacer(Modifier.height(6.dp))
                MontRow(label = "Back", active = false) { choosing = false }
            } else {
                if (item.kind == DesktopStore.Kind.WIDGET) {
                    MontRow(label = "Resize") { choosing = true }
                }
                MontRow(label = "Move") { onMove() }
                MontRow(label = "Remove from the desktop") { onRemove() }
                Spacer(Modifier.height(6.dp))
                MontRow(label = "Cancel", active = false) { onDismiss() }
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
        // Exactly one cell wide. It was 92 against a cell of 72, so every icon overlapped the one
        // in the next column and a tidy grid came out looking like nothing had snapped at all.
        // A launcher's cell is the icon *and* its name; the name ellipsises rather than the cell
        // growing to hold it.
        Modifier
            .width(Grid.WIDE.dp)
            .secondary { onHold() }
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
private fun Widget(
    item: DesktopStore.Item,
    host: AppWidgetHost?,
    moving: Boolean,
    onHold: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var width by remember(item.id) { mutableStateOf(item.width.toFloat()) }
    var height by remember(item.id) { mutableStateOf(item.height.toFloat()) }

    Box(
        Modifier
            .size(width.dp, height.dp)
            // Right click and hold reach the widget's own menu. A plain tap is left to the widget,
            // which usually has something of its own to do with it.
            .secondary { onHold() }
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
                        listOf(android.util.SizeF(width, height))
                    )
                } else {
                    @Suppress("DEPRECATION")
                    view.updateAppWidgetSize(
                        null, width.toInt(), height.toInt(), width.toInt(), height.toInt()
                    )
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
                packageName = info.provider.packageName,
                provider = info.provider,
                width = size(info, density, horizontal = true),
                height = size(info, density, horizontal = false),
                // What the provider drew of itself. Falling back to its icon rather than to
                // nothing: a picker of blank rectangles is a list with worse spacing.
                preview = runCatching {
                    (info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0))?.toBitmap()
                }.getOrNull()
            )
        }.sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    /**
     * How big to make a widget before anybody has dragged it.
     *
     * `minWidth` is what it says: the smallest the provider will tolerate, not the size it was
     * drawn for. Give a widget exactly its minimum and it lays its contents out in sequence until
     * it runs out of room and clips the rest — which is what a launcher never does, because a
     * launcher hands it whole cells.
     *
     * So the cell count is used where the provider gives one, at the size a cell actually is, and
     * the minimum is only a floor under that.
     */
    /**
     * The sizes a widget will actually take, in whole cells.
     *
     * Read from the provider rather than offered freely: one that declares four by two and refuses
     * to resize will letterbox itself into anything else, and offering a size a widget rejects is
     * offering a broken widget. Its resize flags say which way it will stretch at all.
     */
    fun sizes(context: Context, widgetId: Int): List<Pair<Int, Int>> = runCatching {
        if (widgetId == 0) return@runCatching emptyList()
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)
            ?: return@runCatching emptyList()
        val density = context.resources.displayMetrics.density

        val minColumns = cellsFor(info.minWidth, density, WIDE)
        val minRows = cellsFor(info.minHeight, density, TALL)
        val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0

        val maxColumns = if (!horizontal) minColumns else {
            val declared = cellsFor(info.maxResizeWidth, density, WIDE)
            if (declared > minColumns) declared else minColumns + 3
        }
        val maxRows = if (!vertical) minRows else {
            val declared = cellsFor(info.maxResizeHeight, density, TALL)
            if (declared > minRows) declared else minRows + 2
        }

        buildList {
            for (columns in minColumns..maxColumns.coerceAtMost(minColumns + 5)) {
                for (rows in minRows..maxRows.coerceAtMost(minRows + 3)) add(columns to rows)
            }
        }
    }.getOrDefault(emptyList())

    private fun cellsFor(px: Int, density: Float, step: Int): Int {
        if (px <= 0) return 0
        return kotlin.math.ceil((px / density) / step).toInt().coerceAtLeast(1)
    }

    private fun size(
        info: android.appwidget.AppWidgetProviderInfo,
        density: Float,
        horizontal: Boolean
    ): Int {
        val minimum = ((if (horizontal) info.minWidth else info.minHeight) / density).toInt()
        val cells = if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (horizontal) info.targetCellWidth else info.targetCellHeight
        } else {
            0
        }
        // A launcher cell is about seventy dp across once its gaps are counted. Two of them is the
        // smallest thing worth putting on a desktop.
        val step = if (horizontal) WIDE else TALL
        val fromCells = cells * step
        return maxOf(minimum, fromCells, if (horizontal) WIDE * 2 else TALL)
            .coerceAtMost(640)
    }

    /** The same cell the desktop's grid uses, and the same reason it is not square. */
    private const val WIDE = 72
    private const val TALL = 96

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
    val packageName: String,
    val provider: ComponentName,
    val width: Int,
    val height: Int,
    val preview: ImageBitmap?
)
