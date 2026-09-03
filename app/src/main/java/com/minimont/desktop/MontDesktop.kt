@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.minimont.desktop

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.minimont.ui.mont.LocalMontScale
import com.minimont.ui.mont.Mont
import com.minimont.ui.mont.MontAccent
import com.minimont.ui.mont.MontCard
import com.minimont.ui.mont.MontChips
import com.minimont.ui.mont.MontDetail
import com.minimont.ui.mont.MontLabel
import com.minimont.ui.mont.MontRow
import com.minimont.ui.mont.MontSurface
import com.minimont.ui.mont.MontToggle
import com.minimont.ui.mont.MontWhite
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

/**
 * How big an icon is, and how much card there is around it.
 *
 * The icons were 44 and are two thirds of that. At full size the dock is a wall along the bottom of
 * the screen; the whole point of it is to be found without being looked at, and a smaller target is
 * still a target when it is the only thing down there.
 */
/**
 * The gap between whatever is open and the bar under it.
 *
 * Follows the bar's own padding, so the three bands — screen edge, bar, card — are evenly spaced
 * rather than being two decisions that happen to sit near each other.
 */
private val DesktopStore.Thickness.gap: Int get() = padding

/** What the chrome can have open above the dock. At most one, because two is a window manager. */
private enum class Panel {
    NONE, APPS, SETTINGS, ITEM, CALENDAR, NOTIFICATIONS, QUICK, WINDOWS, WIDGETS,
    CLOCK_MENU, BATTERY_MENU, NOTIFICATION_MENU
}

/** The phone's own screens, named by what they do rather than by which class does them. */
private object Phone {
    const val ALARMS = "android.intent.action.SHOW_ALARMS"
    const val DATE_TIME = "android.settings.DATE_SETTINGS"
    const val BATTERY = "android.intent.action.POWER_USAGE_SUMMARY"
    const val BATTERY_SAVER = "android.settings.BATTERY_SAVER_SETTINGS"
    const val NOTIFICATIONS = "android.settings.NOTIFICATION_SETTINGS"
    const val APP_INFO = "android.settings.APPLICATION_DETAILS_SETTINGS"
}

/**
 * Everything miniMont draws above the windows: the dock, the status card, and whatever card is
 * open above them.
 *
 * The root is only as tall as what is in it. The chrome's window wraps that height, so the rest of
 * the display belongs to the windows underneath and a touch meant for an app is not swallowed by an
 * invisible sheet of dock.
 *
 * @param running the packages the host says are open, in launch order.
 */
@Composable
fun MontDesktop(
    running: List<String>,
    onLaunch: (String) -> Unit,
    onClose: (String) -> Unit,
    onPickImage: () -> Unit,
    onGrantNotifications: () -> Unit,
    onWifi: (Boolean) -> Unit,
    onBatterySaver: (Boolean) -> Unit,
    onFit: (String) -> Unit,
    onArea: (Int, Int, Int, Int) -> Unit,
    onOpenPhone: (String) -> Unit,
    onArrangeWindow: (String, String) -> Unit,
    onSpawnWindow: (String) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    desktops: List<List<String>>,
    desktop: Int,
    armed: Boolean,
    onArm: () -> Unit,
    onShowDesktop: (Int) -> Unit,
    onAddDesktop: () -> Unit,
    onRemoveDesktop: (Int) -> Unit
) {
    val context = LocalContext.current
    val store by DesktopStore.state.collectAsState()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var selected by remember { mutableStateOf<DesktopApp?>(null) }
    var section by remember { mutableStateOf(Section.WALLPAPER) }

    // The desktop's own menu is drawn in the backdrop, at the bottom of the window stack, and the
    // cards it asks for belong up here. It says which one it wants; this opens it.
    val asked by DesktopRequests.asked.collectAsState()
    LaunchedEffect(asked) {
        when (asked) {
            DesktopRequests.Panel.WIDGETS -> panel = Panel.WIDGETS
            DesktopRequests.Panel.WALLPAPER -> {
                section = Section.WALLPAPER
                panel = Panel.SETTINGS
            }
            DesktopRequests.Panel.SETTINGS -> panel = Panel.SETTINGS
            DesktopRequests.Panel.NONE -> panel = Panel.NONE
            null -> return@LaunchedEffect
        }
        DesktopRequests.answered()
    }

    val apps = remember { AppCatalog.apps(context) }

    // The area windows are allowed to open in: the display, less the taskbar and its own padding on
    // every other side. Measured rather than assumed — the bar's height is whatever is standing in
    // it — and told to the host, which draws none of this and cannot work it out.
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var bar by remember { mutableStateOf(0) }
    LaunchedEffect(bar, configuration, store.thickness, store.side) {
        if (bar == 0) return@LaunchedEffect
        with(density) {
            val inset = (store.thickness.padding.dp * scaleOf(configuration)).roundToPx()
            val width = configuration.screenWidthDp.dp.roundToPx()
            val height = configuration.screenHeightDp.dp.roundToPx()
            // The bar's own measurement is taken off the edge it is standing on, and the ordinary
            // inset off the other three. It is the same number wherever the bar goes, which is why
            // this is one expression rather than four.
            onArea(
                inset + if (store.side == DesktopStore.Side.LEFT) bar else 0,
                inset + if (store.side == DesktopStore.Side.TOP) bar else 0,
                width - inset - if (store.side == DesktopStore.Side.RIGHT) bar else 0,
                height - inset - if (store.side == DesktopStore.Side.BOTTOM) bar else 0
            )
        }
    }

    // Pinned first, in the order they were pinned, then anything running that is not pinned. A dock
    // whose icons move as apps open is a dock you have to read before you can aim at it.
    val docked = remember(store.pinned, running, apps) {
        val byPackage = apps.associateBy { it.packageName }
        (store.pinned + running.filterNot { it in store.pinned }).mapNotNull { byPackage[it] }
    }

    val side = store.side
    val gap = store.thickness.gap.dp * LocalMontScale.current

    val barContent = @Composable {
            Taskbar(
                apps = docked,
                running = running,
                thickness = store.thickness,
                side = store.side,
                armed = armed,
                onStart = { panel = if (panel == Panel.APPS) Panel.NONE else Panel.APPS },
                // Launching an app that is already open brings its window forward, which is what
                // clicking a taskbar icon means in every desktop anybody has used.
                onOpen = { app ->
                    panel = Panel.NONE
                    onLaunch(app.component)
                },
                onHold = { app ->
                    selected = app
                    panel = Panel.ITEM
                },
                onMeasured = { bar = it },
                onBack = onBack,
                onHome = onHome,
                onWindows = { panel = if (panel == Panel.WINDOWS) Panel.NONE else Panel.WINDOWS },
                onClock = { panel = if (panel == Panel.CALENDAR) Panel.NONE else Panel.CALENDAR },
                onClockMenu = { panel = Panel.CLOCK_MENU },
                onBattery = { panel = if (panel == Panel.QUICK) Panel.NONE else Panel.QUICK },
                onBatteryMenu = { panel = Panel.BATTERY_MENU },
                onNotifications = {
                    panel = if (panel == Panel.NOTIFICATIONS) Panel.NONE else Panel.NOTIFICATIONS
                },
                onNotificationMenu = { panel = Panel.NOTIFICATION_MENU },
                onArm = onArm
            )
    }

    val cards = @Composable {

            // The two cards that belong to the right end of the bar open toward the right, where a
            // shade has always been; everything else opens in the middle, above whatever opened it.
            // Both float clear of the edge by the bar's own padding — a card held against the side of
            // the screen stops being a card and becomes a sidebar, which is a different object with
            // different rules.
            val gap = store.thickness.padding.dp * LocalMontScale.current
            val rightHand = panel == Panel.NOTIFICATIONS || panel == Panel.QUICK
            Box(
                Modifier.fillMaxWidth().padding(horizontal = gap),
                contentAlignment = if (rightHand) Alignment.CenterEnd else Alignment.Center
            ) {

            when (panel) {
                Panel.APPS -> StartMenu(
                    apps = apps,
                    settings = store,
                    bar = with(density) { bar.toDp() }.value.toInt(),
                    area = store.thickness.padding,
                    onOpen = { app ->
                        panel = Panel.NONE
                        onLaunch(app.component)
                    },
                    onSettings = { panel = Panel.SETTINGS },
                    onClose = { panel = Panel.NONE }
                )

                Panel.SETTINGS -> SettingsCard(
                    state = store,
                    notifications = Notifications.granted(context),
                    section = section,
                    onSection = { section = it },
                    onPickImage = {
                        panel = Panel.NONE
                        onPickImage()
                    },
                    onGrantNotifications = {
                        panel = Panel.NONE
                        onGrantNotifications()
                    },
                    onClose = { panel = Panel.NONE }
                )

                Panel.ITEM -> selected?.let { app ->
                    ItemCard(
                        app = app,
                        pinned = app.packageName in store.pinned,
                        open = app.packageName in running,
                        onPin = { DesktopStore.togglePin(app.packageName) },
                        onClose = {
                            onClose(app.packageName)
                            panel = Panel.NONE
                        },
                        onFit = {
                            onFit(app.packageName)
                            panel = Panel.NONE
                        },
                        onSpawn = {
                        onSpawnWindow(app.component)
                        panel = Panel.NONE
                    },
                    onArrange = { where ->
                            onArrangeWindow(app.packageName, where)
                            panel = Panel.NONE
                        },
                        onInfo = {
                            panel = Panel.NONE
                            // Android's own page for the app, opened on the desktop like anything else.
                            onOpenPhone("${Phone.APP_INFO} package:${app.packageName}")
                        },
                        onDismiss = { panel = Panel.NONE }
                    )
                }

                Panel.QUICK -> QuickCard(
                    running = running,
                    onWifi = onWifi,
                    onBatterySaver = onBatterySaver,
                    onCloseAll = {
                        running.forEach(onClose)
                        panel = Panel.NONE
                    },
                    onSettings = { panel = Panel.SETTINGS },
                    onClose = { panel = Panel.NONE }
                )

                Panel.WIDGETS -> WidgetPicker { panel = Panel.NONE }

                Panel.WINDOWS -> DesktopsCard(
                    desktops = desktops,
                    current = desktop,
                    apps = apps,
                    onShow = { index ->
                        panel = Panel.NONE
                        onShowDesktop(index)
                    },
                    onAdd = onAddDesktop,
                    onRemove = onRemoveDesktop,
                    onDismiss = { panel = Panel.NONE }
                )

                Panel.CLOCK_MENU -> MenuCard(
                    title = "CLOCK",
                    rows = listOf(
                        "Alarms" to Phone.ALARMS,
                        "Date and time" to Phone.DATE_TIME
                    ),
                    onOpen = { action ->
                        panel = Panel.NONE
                        onOpenPhone(action)
                    },
                    onDismiss = { panel = Panel.NONE }
                )

                Panel.BATTERY_MENU -> MenuCard(
                    title = "BATTERY",
                    rows = listOf(
                        "Battery usage" to Phone.BATTERY,
                        "Battery saver" to Phone.BATTERY_SAVER
                    ),
                    onOpen = { action ->
                        panel = Panel.NONE
                        onOpenPhone(action)
                    },
                    onDismiss = { panel = Panel.NONE }
                )

                Panel.NOTIFICATION_MENU -> MenuCard(
                    title = "NOTIFICATIONS",
                    rows = listOf("Notification settings" to Phone.NOTIFICATIONS),
                    leading = {
                        MontRow(label = "Clear all") {
                            Notifications.dismissAll()
                            panel = Panel.NONE
                        }
                    },
                    onOpen = { action ->
                        panel = Panel.NONE
                        onOpenPhone(action)
                    },
                    onDismiss = { panel = Panel.NONE }
                )

                Panel.CALENDAR -> CalendarCard { panel = Panel.NONE }

                Panel.NOTIFICATIONS -> NotificationsCard { panel = Panel.NONE }

                Panel.NONE -> Unit
            }
            }
    }

    // The bar holds its edge and the cards open away from it. A row and a column are not
    // the same object with a flag on it, so there is one layout each way rather than a clever
    // one doing both — which is how padding ends up on the wrong side.
    when (side) {
        DesktopStore.Side.BOTTOM -> Column(Modifier.fillMaxWidth()) {
            cards()
            Spacer(Modifier.height(gap))
            barContent()
        }

        DesktopStore.Side.TOP -> Column(Modifier.fillMaxWidth()) {
            barContent()
            Spacer(Modifier.height(gap))
            cards()
        }

        DesktopStore.Side.LEFT -> Row(Modifier.fillMaxHeight()) {
            barContent()
            Spacer(Modifier.width(gap))
            cards()
        }

        DesktopStore.Side.RIGHT -> Row(Modifier.fillMaxHeight()) {
            cards()
            Spacer(Modifier.width(gap))
            barContent()
        }
    }
}

/**
 * A Mont card, sized to a desktop.
 *
 * On the cover display a surface is full width and hard against an edge; here it floats and stops
 * where it is finished, because full width on a monitor is a rectangle a metre long holding four
 * words. The rule underneath — a surface goes to the edge it belongs to — is the same one.
 */
@Composable
private fun DesktopCard(
    width: Int = 360,
    maxHeight: Int = 420,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val scale = LocalMontScale.current
    MontCard(
        Modifier
            .width(width.dp * scale)
            .heightIn(max = maxHeight.dp * scale)
            // The card swallows every press that lands on it, including the ones on its own empty
            // margins, so that pressing inside a card is never mistaken for pressing beside one.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        content = content
    )
}

/**
 * The app drawer: every application, as a list or as a grid.
 *
 * A list is a column of names, which is the fastest thing to read and the slowest thing to aim at.
 * A grid is a field of icons, which is the opposite. Neither is right for everybody, so it is a
 * setting rather than an argument.
 */
@Composable
private fun StartMenu(
    apps: List<DesktopApp>,
    settings: DesktopStore.State,
    /** The taskbar's height and its padding, in dp: what is left is the fillable area. */
    bar: Int,
    area: Int,
    onOpen: (DesktopApp) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit
) {
    val scale = LocalMontScale.current
    val configuration = LocalConfiguration.current

    // SuperFill is a different layout, not a taller one. A list that reaches the top of the screen
    // is still one column of names with the whole display beside it doing nothing, so SuperFill
    // takes the fillable area — everything above the taskbar — and puts a grid in it that works out
    // its own columns from the width it was handed.
    val filling = settings.superFill
    val width = if (filling) configuration.screenWidthDp - 2 * area
        else if (settings.drawer == DesktopStore.Drawer.GRID) 520 else 360
    val cap = if (filling) configuration.screenHeightDp - bar - 3 * area else 420
    val columns = if (filling) (width / CELL).coerceIn(3, 12) else settings.drawerColumns

    DesktopCard(width = width, maxHeight = cap) {
        MontLabel("APPS", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * scale))

        when {
            filling || settings.drawer == DesktopStore.Drawer.GRID ->
                Grid(apps, settings, columns, onOpen)

            else -> apps.forEach { app ->
                MontRow(label = app.label) { onOpen(app) }
            }
        }

        Spacer(Modifier.height(10.dp * scale))
        MontRow(label = "Settings", active = false) { onSettings() }
        MontRow(label = "Close", active = false) { onClose() }
    }
}

/**
 * Applications in rows of icons, down or sideways.
 *
 * Laid out by hand rather than with a lazy grid, because the card it lives in already scrolls and
 * two scrolling containers inside each other is a fight neither of them wins.
 */
@Composable
private fun Grid(
    apps: List<DesktopApp>,
    settings: DesktopStore.State,
    requestedColumns: Int,
    onOpen: (DesktopApp) -> Unit
) {
    val scale = LocalMontScale.current
    val columns = requestedColumns.coerceIn(3, 12)

    if (settings.drawerPaged) {
        // Sideways, in pages: a page is as many rows as fit beside the columns, so a page is always
        // a rectangle of icons rather than a run that stops wherever it ran out.
        val perPage = columns * 4
        val pages = apps.chunked(perPage)
        val state = rememberPagerState { pages.size.coerceAtLeast(1) }
        Column {
            HorizontalPager(state = state) { index ->
                Column {
                    pages.getOrNull(index).orEmpty().chunked(columns).forEach { row ->
                        GridRow(row, columns, settings.drawerTitles, onOpen)
                    }
                }
            }
            if (pages.size > 1) {
                Spacer(Modifier.height(8.dp * scale))
                // Selected is simply the bright one, even when the thing selected is a page.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp * scale)) {
                    repeat(pages.size) { index ->
                        MontLabel(
                            "${index + 1}",
                            size = 11,
                            alpha = if (index == state.currentPage) MontWhite.ACTIVE else MontWhite.DIM
                        )
                    }
                }
            }
        }
    } else {
        Column {
            apps.chunked(columns).forEach { row ->
                GridRow(row, columns, settings.drawerTitles, onOpen)
            }
        }
    }
}

@Composable
private fun GridRow(
    row: List<DesktopApp>,
    columns: Int,
    titles: Boolean,
    onOpen: (DesktopApp) -> Unit
) {
    val scale = LocalMontScale.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp * scale)) {
        row.forEach { app ->
            Column(
                Modifier
                    .weight(1f)
                    .combinedClickable { onOpen(app) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val icon = app.icon
                if (icon != null) {
                    Image(icon, contentDescription = app.label, modifier = Modifier.size(40.dp * scale))
                } else {
                    MontLabel(app.label.take(1).uppercase(), size = 20)
                }
                if (titles) {
                    Spacer(Modifier.height(4.dp * scale))
                    MontLabel(app.label, size = 10, alpha = MontWhite.DETAIL)
                }
            }
        }
        // The last row is padded out so its icons sit under the ones above them rather than
        // spreading to fill the width.
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** The categories down the left of the settings card. */
private enum class Section(val label: String) {
    WALLPAPER("Wallpaper"),
    DESKTOP("Desktop"),
    TASKBAR("Taskbar"),
    DRAWER("App drawer"),
    STATUS("Status")
}

/**
 * Settings, as one black card with a sidebar.
 *
 * The categories run down the left and what they hold sits beside them, with nothing drawn between
 * the two — no rule, no divider, no change of shade. Selected is the bright one and the rest are
 * dim, which is the same rule a row, a chip and an open window already follow, and it is enough:
 * a line between two columns is the language admitting the type could not do the job.
 *
 * On a phone a Mont panel opens over the thing it edits and needs no header. A card floating in the
 * middle of a wallpaper is about nothing until it says so, which is why this is the one titled
 * surface in miniMont.
 */
@Composable
private fun SettingsCard(
    state: DesktopStore.State,
    notifications: Boolean,
    section: Section,
    onSection: (Section) -> Unit,
    onPickImage: () -> Unit,
    onGrantNotifications: () -> Unit,
    onClose: () -> Unit
) {
    val scale = LocalMontScale.current

    DesktopCard(width = 560, maxHeight = 400) {
        MontLabel("SETTINGS", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(14.dp * scale))

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(140.dp * scale)) {
                Section.entries.forEach { candidate ->
                    MontRow(
                        label = candidate.label,
                        active = candidate == section
                    ) { onSection(candidate) }
                }
            }
            Spacer(Modifier.width(22.dp * scale))
            Column(Modifier.weight(1f)) {
                when (section) {
                    Section.WALLPAPER -> WallpaperSection(state, onPickImage)
                    Section.DESKTOP -> DesktopSection(state)
                    Section.TASKBAR -> TaskbarSection(state)
                    Section.DRAWER -> DrawerSection(state)
                    Section.STATUS -> StatusSection(notifications, onGrantNotifications)
                }
            }
        }

        Spacer(Modifier.height(14.dp * scale))
        MontRow(label = "Close", active = false) { onClose() }
    }
}

@Composable
private fun WallpaperSection(state: DesktopStore.State, onPickImage: () -> Unit) {
    DesktopStore.Backdrop.entries.forEach { backdrop ->
        if (backdrop == DesktopStore.Backdrop.IMAGE) {
            MontRow(
                label = backdrop.label,
                trailing = if (state.image == null) "choose" else "change",
                active = state.backdrop == backdrop
            ) {
                if (state.image == null) onPickImage() else DesktopStore.setBackdrop(backdrop)
            }
            if (state.image != null) {
                MontRow(label = "Choose another picture", active = false) { onPickImage() }
            }
        } else {
            MontRow(
                label = backdrop.label,
                active = state.backdrop == backdrop
            ) { DesktopStore.setBackdrop(backdrop) }
        }
    }
}

/**
 * What is on the desktop, and how to put something else there.
 *
 * Widgets are listed by what they are rather than by which app provides them, because that is how
 * anybody looks for one. Each arrives at the size its provider says it wants, in the top left, and
 * is dragged from there — miniMont does not guess where somebody wanted it.
 */
@Composable
private fun DesktopSection(state: DesktopStore.State) {
    val scale = LocalMontScale.current
    MontDetail(
        when (state.items.size) {
            0 -> "Nothing on the desktop."
            1 -> "One thing on the desktop."
            else -> "${state.items.size} things on the desktop."
        }
    )
    Spacer(Modifier.height(10.dp * scale))

    if (state.items.isNotEmpty()) {
        MontRow(label = "Clear the desktop", active = false) {
            state.items.forEach { DesktopStore.removeItem(it.id) }
        }
    }
    Spacer(Modifier.height(8.dp * scale))
    MontDetail(
        "Right click the desktop to add a widget or an application, and drag either one wherever " +
            "you want it."
    )
}

/**
 * The widget picker: what each one looks like, not what it is called.
 *
 * A list of provider names is a list of words like "Clock 4x1" and "At a Glance", which tell you
 * nothing about what you are about to put on your desktop. Providers draw a preview of themselves
 * for exactly this, so the picker shows that and puts the name under it — and where a provider drew
 * no preview, its icon, because a grid with one blank rectangle in it is still readable and a grid
 * of blank rectangles is a list with worse spacing.
 */
@Composable
private fun WidgetPicker(onClose: () -> Unit) {
    val context = LocalContext.current
    val scale = LocalMontScale.current
    val providers = remember { Widgets.providers(context) }

    DesktopCard(width = 560, maxHeight = 420) {
        MontLabel("WIDGETS", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * scale))

        if (providers.isEmpty()) MontDetail("Nothing installed offers one.")

        // Grouped by the application that provides them, because that is how anybody looks: you
        // want the clock's widget, not a widget called Clock 4x1 among forty other names.
        providers.groupBy { it.packageName }.forEach { (packageName, group) ->
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp * scale, bottom = 4.dp * scale),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppCatalog.byPackage(context, packageName)?.icon?.let { icon ->
                    Image(icon, contentDescription = null, modifier = Modifier.size(16.dp * scale))
                    Spacer(Modifier.width(7.dp * scale))
                }
                MontLabel(group.first().app.uppercase(), size = 11, alpha = MontWhite.DETAIL)
            }

        group.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp * scale)) {
                row.forEach { provider ->
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp * scale)
                            .combinedClickable { add(context, provider); onClose() },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(86.dp * scale)
                                .background(Color.White.copy(alpha = MontWhite.TRACK)),
                            contentAlignment = Alignment.Center
                        ) {
                            provider.preview?.let { preview ->
                                Image(
                                    preview,
                                    contentDescription = provider.label,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp * scale))
                        MontLabel(provider.label, size = 11, alpha = MontWhite.PRIMARY)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        }

        Spacer(Modifier.height(10.dp * scale))
        MontRow(label = "Close", active = false) { onClose() }
    }
}

/**
 * Bind a widget and put it on the desktop.
 *
 * Bound at the moment of choosing rather than when it is first drawn: one that cannot be bound
 * should fail while somebody is looking at a picker, not as an empty rectangle discovered later.
 */
private fun add(context: android.content.Context, provider: AppWidgetProviderEntry) {
    val host = android.appwidget.AppWidgetHost(context, Widgets.HOST_ID)
    val id = Widgets.bind(context, host, provider.provider)
    if (id == 0) return
    val where = DesktopStore.nextCell()
    DesktopStore.addItem(
        DesktopStore.Item(
            id = "w" + System.currentTimeMillis(),
            kind = DesktopStore.Kind.WIDGET,
            component = provider.provider.flattenToShortString(),
            x = where.first,
            y = where.second,
            width = provider.width,
            height = provider.height,
            widgetId = id
        )
    )
}

/**
 * How thick the bar is.
 *
 * Three steps and small ones, and each moves the icons, the padding and the type together — the
 * bar's height is set by the clock stack rather than by the icons, so a thickness that only changed
 * the icons would change nothing. The type never goes below the sizes Mont already uses for a clock
 * and an explanatory line: a thinner bar you cannot read the date on is not thinner, it is broken.
 */
@Composable
private fun TaskbarSection(state: DesktopStore.State) {
    val scale = LocalMontScale.current
    val options = DesktopStore.Thickness.entries
    MontLabel("POSITION", size = 11, alpha = MontWhite.DETAIL)
    Spacer(Modifier.height(6.dp * scale))
    val sides = DesktopStore.Side.entries
    MontChips(
        options = sides.map { it.label },
        selected = sides.indexOf(state.side)
    ) { index -> DesktopStore.setSide(sides[index]) }

    Spacer(Modifier.height(14.dp * scale))
    MontLabel("THICKNESS", size = 11, alpha = MontWhite.DETAIL)
    Spacer(Modifier.height(6.dp * scale))
    MontChips(
        options = options.map { it.label },
        selected = options.indexOf(state.thickness)
    ) { index -> DesktopStore.setThickness(options[index]) }
    Spacer(Modifier.height(10.dp * scale))
    MontDetail("Regular is the one everything else was drawn against.")
}

@Composable
private fun DrawerSection(state: DesktopStore.State) {
    val scale = LocalMontScale.current
    val modes = DesktopStore.Drawer.entries

    MontLabel("LAYOUT", size = 11, alpha = MontWhite.DETAIL)
    Spacer(Modifier.height(6.dp * scale))
    MontChips(
        options = modes.map { it.label },
        selected = modes.indexOf(state.drawer)
    ) { index -> DesktopStore.setDrawer(modes[index]) }

    if (state.drawer == DesktopStore.Drawer.GRID || state.superFill) {
        Spacer(Modifier.height(12.dp * scale))
        MontLabel(
            "COLUMNS",
            size = 11,
            alpha = if (state.superFill) MontWhite.DISABLED else MontWhite.DETAIL
        )
        Spacer(Modifier.height(6.dp * scale))
        val columns = listOf(3, 4, 5, 6, 7, 8)
        if (state.superFill) {
            // Left visible and left alone. Hiding the setting would make it look as though it had
            // gone; dimming it says it is still yours and something else is deciding it for now.
            MontDetail("Worked out from the width while SuperFill is on.")
        } else {
            MontChips(
                options = columns.map { "$it" },
                selected = columns.indexOf(state.drawerColumns)
            ) { index -> DesktopStore.setDrawerColumns(columns[index]) }
        }

        Spacer(Modifier.height(10.dp * scale))
        SettingToggle("Names under icons", state.drawerTitles) { DesktopStore.setDrawerTitles(it) }
    }

    Spacer(Modifier.height(6.dp * scale))
    SettingToggle("Sideways in pages", state.drawerPaged) { DesktopStore.setDrawerPaged(it) }
    SettingToggle("SuperFill", state.superFill) { DesktopStore.setSuperFill(it) }
    Spacer(Modifier.height(6.dp * scale))
    MontDetail("SuperFill lets the drawer take the screen when it has more than fits.")
}

@Composable
private fun StatusSection(granted: Boolean, onGrant: () -> Unit) {
    if (granted) {
        MontDetail("Notifications are being counted.")
    } else {
        // Asked for here rather than at the door: the desktop works without it, and a permission
        // demanded before anything has been shown is a permission nobody grants.
        MontRow(label = "Show the notification count") { onGrant() }
        Spacer(Modifier.height(6.dp * LocalMontScale.current))
        MontDetail("miniMont keeps one number, and reads nothing it does not show.")
    }
}

/** A row with a Mont toggle at the end of it. Never a Material switch. */
@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp * LocalMontScale.current),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MontLabel(label.uppercase(), Modifier.weight(1f), alpha = MontWhite.ACTIVE)
        MontToggle(checked, onChange)
    }
}

/** What can be done to one dock item. Held rather than tapped, so a tap stays a launch. */
@Composable
private fun ItemCard(
    app: DesktopApp,
    pinned: Boolean,
    open: Boolean,
    onPin: () -> Unit,
    onClose: () -> Unit,
    onFit: () -> Unit,
    onInfo: () -> Unit,
    onArrange: (String) -> Unit,
    onSpawn: () -> Unit,
    onDismiss: () -> Unit
) {
    val scale = LocalMontScale.current
    DesktopCard(width = 300, maxHeight = 340) {
        MontLabel(app.label.uppercase(), size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * LocalMontScale.current))
        MontRow(label = if (pinned) "Unpin from the taskbar" else "Pin to the taskbar") {
            onPin()
            onDismiss()
        }
        MontRow(label = "New window") { onSpawn() }
        MontRow(label = "App info") { onInfo() }

        if (open) {
            Spacer(Modifier.height(10.dp * scale))
            MontLabel("ARRANGE", size = 11, alpha = MontWhite.DETAIL)
            Spacer(Modifier.height(4.dp * scale))
            listOf(
                "Fill the screen" to "fill",
                "Left half" to "left",
                "Right half" to "right",
                "Top left" to "tl",
                "Top right" to "tr",
                "Bottom left" to "bl",
                "Bottom right" to "br"
            ).forEach { (label, where) ->
                MontRow(label = label) { onArrange(where) }
            }
            Spacer(Modifier.height(10.dp * scale))
        }
        MontRow(label = "Add to the desktop") {
            val where = DesktopStore.nextCell()
            DesktopStore.addItem(
                DesktopStore.Item(
                    id = "a" + System.currentTimeMillis(),
                    kind = DesktopStore.Kind.APP,
                    component = app.component,
                    x = where.first,
                    y = where.second
                )
            )
            onDismiss()
        }
        // The window that needs this most is the one whose corners are already off the screen, and
        // that is exactly the window you cannot drag back.
        MontRow(label = "Fit to the screen", enabled = open) { onFit() }
        // Closing means the program is gone, not backgrounded. A window that reopens with the state
        // you closed it in is a window that did not close.
        MontRow(label = "Close", enabled = open) { onClose() }
        Spacer(Modifier.height(10.dp * LocalMontScale.current))
        MontRow(label = "Cancel", active = false) { onDismiss() }
    }
}

/**
 * The taskbar.
 *
 * A full-width Mont surface along the bottom edge — no gap to the sides, no gap underneath, because
 * a bar *is* the edge of the screen.
 *
 * Three things stand in it, and they are the three things a desktop's bottom edge has always held:
 * the way back at the left, what you can open in the middle, and what is true at the right.
 */
@Composable
private fun Taskbar(
    apps: List<DesktopApp>,
    running: List<String>,
    thickness: DesktopStore.Thickness,
    side: DesktopStore.Side,
    armed: Boolean,
    onStart: () -> Unit,
    onOpen: (DesktopApp) -> Unit,
    onHold: (DesktopApp) -> Unit,
    onMeasured: (Int) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onWindows: () -> Unit,
    onClock: () -> Unit,
    onClockMenu: () -> Unit,
    onBattery: () -> Unit,
    onBatteryMenu: () -> Unit,
    onNotifications: () -> Unit,
    onNotificationMenu: () -> Unit,
    onArm: () -> Unit
) {
    val scale = LocalMontScale.current
    val vertical = side.vertical

    // Along its edge: a band across the screen, or a column down it. Measured on the axis it does
    // not span, which is the number everything else needs — how much of the display the bar has
    // taken away from the windows.
    Box(
        Modifier
            .then(if (vertical) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
            .background(MontSurface)
            .onSizeChanged { onMeasured(if (vertical) it.width else it.height) }
            .padding(thickness.padding.dp * scale)
    ) {
        Navigation(
            Modifier.align(if (vertical) Alignment.TopCenter else Alignment.CenterStart),
            thickness = thickness,
            vertical = vertical,
            armed = armed,
            onBack = onBack,
            onHome = onHome,
            onWindows = onWindows,
            onArm = onArm
        )
        TaskbarRun(
            Modifier.align(Alignment.Center),
            vertical = vertical,
            spacing = 9.dp * scale
        ) {
            // Built the same way a taskbar item is, dot and all — the dot simply never drawn.
            //
            // The mustard grid was a bare icon standing beside columns of icon-gap-dot, so the row
            // centred two different shapes against each other and every application sat higher
            // than the thing that opens them. Same column, same reserved space underneath, and
            // they line up because they are the same object.
            TaskbarItem(thickness, running = false) {
                StartSquares(Modifier.fillMaxSize(), onStart)
            }
            apps.forEach { app ->
                DockItem(app, thickness, app.packageName in running, { onOpen(app) }, { onHold(app) })
            }
        }
        StatusBlock(
            Modifier.align(if (vertical) Alignment.BottomCenter else Alignment.CenterEnd),
            thickness = thickness,
            vertical = vertical,
            onClock = onClock,
            onClockMenu = onClockMenu,
            onBattery = onBattery,
            onBatteryMenu = onBatteryMenu,
            onNotifications = onNotifications,
            onNotificationMenu = onNotificationMenu
        )
    }
}

/** A row or a column, depending on which way the bar is running. */
@Composable
private fun TaskbarRun(
    modifier: Modifier = Modifier,
    vertical: Boolean,
    spacing: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit
) {
    if (vertical) {
        Column(
            modifier,
            verticalArrangement = Arrangement.spacedBy(spacing),
            horizontalAlignment = Alignment.CenterHorizontally
        ) { content() }
    } else {
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically
        ) { content() }
    }
}

/**
 * Back, home and recents, at the left.
 *
 * All three mean something *inside* miniMont and nothing outside it. Home shows the desktop rather
 * than leaving for the phone's launcher — leaving would end the thing you are using. Recents lists
 * what is open here, not what Android has been doing. Back is the only one that is what it looks
 * like: it goes back in whatever has focus.
 *
 * The shapes are Android's own — arrow, circle, square. Mont's objection is to saying a thing with
 * a little picture when a word would do, and here a word does not: these three have been the same
 * three marks on every Android phone for fifteen years, and nobody reads the word on a control they
 * hit without looking. Drawn as strokes at one weight, dim at rest and bright under the finger,
 * which is the only state the language gives anything.
 */
@Composable
private fun Navigation(
    modifier: Modifier = Modifier,
    thickness: DesktopStore.Thickness,
    vertical: Boolean,
    armed: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onWindows: () -> Unit,
    onArm: () -> Unit
) {
    val scale = LocalMontScale.current
    TaskbarRun(modifier, vertical = vertical, spacing = 10.dp * scale) {
        NavMark(Mark.BACK, thickness, onClick = onBack)
        // Home twice arms a right click.
        //
        // A double tap on home would otherwise be showing the desktop and coming straight back —
        // in and out, which is nothing — so the gesture was free. It stays nothing: the single tap
        // is held until the double-tap window has passed, so arming never flashes the desktop on
        // the way. The mark turns mustard and stays that way until a click is spent, because a
        // mode you cannot see is a mode you will be surprised by.
        NavMark(
            Mark.HOME,
            thickness,
            colour = if (armed) MontAccent.Mustard else null,
            onClick = onHome,
            onDoubleClick = onArm
        )
        NavMark(Mark.RECENTS, thickness, onClick = onWindows)
    }
}

private enum class Mark { BACK, HOME, RECENTS }

@Composable
private fun NavMark(
    mark: Mark,
    thickness: DesktopStore.Thickness,
    colour: Color? = null,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null
) {
    val scale = LocalMontScale.current
    var held by remember { mutableStateOf(false) }
    val alpha = if (held || colour != null) MontWhite.ACTIVE else MontWhite.DIM

    Box(
        Modifier
            .size(thickness.icon.dp * scale)
            .pointerInput(mark, onDoubleClick) {
                detectTapGestures(
                    onPress = {
                        held = true
                        // Without a double tap to wait for, the press is the click: a control that
                        // answers on release when it does not have to is a control that feels slow.
                        if (onDoubleClick == null) onClick()
                        tryAwaitRelease()
                        held = false
                    },
                    onTap = { if (onDoubleClick != null) onClick() },
                    onDoubleTap = { onDoubleClick?.invoke() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size((thickness.icon / 2 + 1).dp * scale)) {
            val colour = colour ?: Color.White.copy(alpha = alpha)
            val line = Stroke(width = 1.6f * scale)
            when (mark) {
                // A chevron: two strokes meeting at a point, and no shaft. An arrow points at
                // something; back is a direction, and the circle and the square beside it are
                // outlines of the same weight rather than objects with parts.
                Mark.BACK -> {
                    val middle = size.height / 2f
                    val tip = size.width * .28f
                    val reach = size.width * .78f
                    drawLine(colour, Offset(reach, middle - (reach - tip)), Offset(tip, middle), line.width)
                    drawLine(colour, Offset(tip, middle), Offset(reach, middle + (reach - tip)), line.width)
                }

                Mark.HOME -> drawCircle(
                    colour,
                    radius = size.minDimension / 2f - line.width / 2f,
                    style = line
                )

                Mark.RECENTS -> drawRect(
                    colour,
                    topLeft = Offset(line.width / 2f, line.width / 2f),
                    size = Size(size.width - line.width, size.height - line.width),
                    style = line
                )
            }
        }
    }
}

/**
 * The first thing in the taskbar: a four by four grid of mustard squares.
 *
 * Sixteen squares rather than an icon, because Mont will not say a thing with a little picture when
 * a shape will do, and because this is the one control on the desktop that is not any application's
 * — it belongs to miniMont, and it is drawn in miniMont's own accent.
 */
@Composable
private fun StartSquares(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Canvas(modifier.combinedClickable(onClick = onClick)) {
        val cell = size.width / 4f
        val square = cell * .62f
        val inset = (cell - square) / 2f
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                drawRect(
                    MontAccent.Mustard,
                    Offset(column * cell + inset, row * cell + inset),
                    Size(square, square)
                )
            }
        }
    }
}

/**
 * An application in the taskbar.
 *
 * Full strength whether it is open or not. Dimming the closed ones made half the bar look broken —
 * these are somebody's own icons, drawn to be seen at full strength, and taking 42% off them says
 * "disabled" rather than "not currently open".
 *
 * The open ones get a small mustard square below the icon instead, clear of it rather than sitting
 * on its bottom edge. An addition to what is running, rather than a subtraction from what is not.
 */
/**
 * One thing standing in the taskbar: an icon, and the room a dot takes underneath it.
 *
 * The room is reserved whether the dot is drawn or not, so everything in the bar is the same shape
 * and lines up on the same centre.
 */
@Composable
private fun TaskbarItem(
    thickness: DesktopStore.Thickness,
    running: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    val scale = LocalMontScale.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(thickness.icon.dp * scale), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(Modifier.height(3.dp * scale))
        // Drawn or not drawn. Nothing announces itself, so there is no faint dot for a closed app.
        Canvas(Modifier.size(width = 5.dp * scale, height = 3.dp * scale)) {
            if (running) drawRect(MontAccent.Mustard, size = size)
        }
    }
}

@Composable
private fun DockItem(
    app: DesktopApp,
    thickness: DesktopStore.Thickness,
    running: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit
) {
    TaskbarItem(
        thickness = thickness,
        running = running,
        modifier = Modifier
            .secondary { onHold() }
            .combinedClickable(onClick = onClick, onLongClick = onHold)
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(icon, contentDescription = app.label, modifier = Modifier.fillMaxSize())
        } else {
            MontLabel(app.label.take(1).uppercase(), size = 20, alpha = MontWhite.ACTIVE)
        }
    }
}

/**
 * What is true, at the right end of the bar.
 *
 * Three things in a row, each the shape of the thing it is: a bubble holding what is waiting, a
 * battery holding how much is left, and the time with the date under it. The grid of four bare
 * numbers this replaces made you work out which number was which every time you looked at it, which
 * is the opposite of what a status bar is for.
 *
 * Each answers a left click with the thing you usually want and a right click with the phone's own
 * screen for it — and a long press does what the right click does, because the tablet has fingers
 * and no second button.
 */
@Composable
private fun StatusBlock(
    modifier: Modifier = Modifier,
    thickness: DesktopStore.Thickness,
    vertical: Boolean = false,
    onClock: () -> Unit,
    onClockMenu: () -> Unit,
    onBattery: () -> Unit,
    onBatteryMenu: () -> Unit,
    onNotifications: () -> Unit,
    onNotificationMenu: () -> Unit
) {
    val context = LocalContext.current
    val scale = LocalMontScale.current
    var now by remember { mutableStateOf(Date()) }
    var battery by remember { mutableStateOf(batteryLevel(context)) }
    val notes by Notifications.notes.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            battery = batteryLevel(context)
            kotlinx.coroutines.delay(10_000)
        }
    }

    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val date = remember(now) { SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now) }
    val level = if (battery in 1..19) MontAccent.LowBattery else MontAccent.Live

    TaskbarRun(modifier, vertical = vertical, spacing = 14.dp * scale) {
        // Nothing announces itself: with nothing waiting there is no bubble, not an empty one.
        if (notes.isNotEmpty()) {
            Bubble(
                count = notes.size,
                modifier = Modifier
                    .secondary { onNotificationMenu() }
                    .combinedClickable(
                        onClick = onNotifications,
                        onLongClick = onNotificationMenu
                    )
            )
        }

        Row(
            Modifier
                .secondary { onBatteryMenu() }
                .combinedClickable(onClick = onBattery, onLongClick = onBatteryMenu),
            horizontalArrangement = Arrangement.spacedBy(5.dp * scale),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MontLabel("$battery%", size = 13, colour = level, alpha = 1f)
            BatteryMark(battery, level, Modifier.size(width = 22.dp * scale, height = 11.dp * scale))
        }

        Column(
            Modifier
                .secondary { onClockMenu() }
                .combinedClickable(onClick = onClock, onLongClick = onClockMenu),
            horizontalAlignment = Alignment.End
        ) {
            MontLabel(time, size = thickness.time, alpha = MontWhite.PRIMARY)
            // Down the side there is no room for a date beside a clock, and a bar you have to widen
            // to read the month is a bar in the wrong shape.
            if (!vertical) MontLabel(date, size = thickness.date, alpha = MontWhite.DETAIL)
        }
    }
}

/**
 * A bubble with a number in it.
 *
 * Square, because Mont rounds nothing, with a tail cut from the same white so it reads as something
 * said rather than as a badge stuck on. White is the language's *active* — this is the one thing on
 * the bar that is asking for something.
 */
@Composable
private fun Bubble(count: Int, modifier: Modifier = Modifier) {
    val scale = LocalMontScale.current
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Box(
            Modifier
                .background(Color.White)
                .padding(horizontal = 5.dp * scale, vertical = 1.dp * scale)
        ) {
            Text(
                if (count > 99) "99" else "$count",
                color = Color.Black,
                fontFamily = Mont,
                fontWeight = FontWeight.Black,
                fontSize = (13 * scale).sp,
                maxLines = 1
            )
        }
        Canvas(Modifier.size(width = 7.dp * scale, height = 4.dp * scale)) {
            val tail = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(tail, Color.White)
        }
    }
}

/**
 * A battery, drawn rather than written.
 *
 * An outline at Mont's border alpha, a fill in the same colour the percentage is written in, and a
 * cap. Vector, not a glyph — the language's objection is to saying things with little pictures when
 * a word would do, and here the word is already standing next to it saying the exact number.
 */
@Composable
private fun BatteryMark(level: Int, colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cap = size.width * .09f
        val body = size.width - cap - 1f
        drawRect(
            Color.White.copy(alpha = MontWhite.BORDER),
            topLeft = Offset.Zero,
            size = Size(body, size.height),
            style = Stroke(width = 1.4f)
        )
        val inset = 2.5f
        val fill = ((body - inset * 2) * (level.coerceIn(0, 100) / 100f)).coerceAtLeast(0f)
        drawRect(colour, Offset(inset, inset), Size(fill, size.height - inset * 2))
        drawRect(
            Color.White.copy(alpha = MontWhite.BORDER),
            Offset(body + 1f, size.height * .3f),
            Size(cap, size.height * .4f)
        )
    }
}

/**
 * The desktops, as a grid of what is on each.
 *
 * This is what the recents mark opens. A list of open windows was already the taskbar's job and
 * telling you twice is not a feature — where those windows *are* is the thing nothing else says.
 *
 * Each desktop is a rectangle holding the icons of what is standing on it: empty ones look empty,
 * busy ones look busy, and you pick by shape rather than by reading a number. The one you are on is
 * the bright one, which is the rule everything else here follows.
 */
@Composable
private fun DesktopsCard(
    desktops: List<List<String>>,
    current: Int,
    apps: List<DesktopApp>,
    onShow: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scale = LocalMontScale.current
    val byPackage = remember(apps) { apps.associateBy { it.packageName } }

    DesktopCard(width = 520, maxHeight = 400) {
        MontLabel("DESKTOPS", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * scale))

        desktops.chunked(3).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp * scale)) {
                row.forEachIndexed { columnIndex, packages ->
                    val index = rowIndex * 3 + columnIndex
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp * scale)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(74.dp * scale)
                                .background(
                                    Color.White.copy(
                                        alpha = if (index == current) .22f else MontWhite.TRACK
                                    )
                                )
                                .combinedClickable { onShow(index) }
                                .padding(6.dp * scale)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp * scale)) {
                                packages.take(5).forEach { name ->
                                    byPackage[name]?.icon?.let { icon ->
                                        Image(
                                            icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp * scale)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(3.dp * scale))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MontLabel(
                                "${index + 1}",
                                Modifier.weight(1f),
                                size = 11,
                                alpha = if (index == current) MontWhite.ACTIVE else MontWhite.DIM
                            )
                            // The first desktop has no X: something has to be left to come back to.
                            if (index > 0) {
                                MontLabel(
                                    "X",
                                    Modifier
                                        .padding(horizontal = 6.dp * scale, vertical = 3.dp * scale)
                                        .combinedClickable { onRemove(index) },
                                    size = 11,
                                    alpha = MontWhite.DIM
                                )
                            }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(10.dp * scale))
        MontRow(label = "Add a desktop") { onAdd() }
        MontRow(label = "Close", active = false) { onDismiss() }
    }
}

/**
 * What is open here, and only here.
 *
 * Android's own recents holds everything the phone has been doing, most of which is not on this
 * screen and none of which this screen put there. This is the desktop's own list: the windows
 * miniMont opened, with the same two things you can do to any of them.
 */
@Composable
private fun WindowsCard(
    apps: List<DesktopApp>,
    onFocus: (DesktopApp) -> Unit,
    onClose: (DesktopApp) -> Unit,
    onDismiss: () -> Unit
) {
    val scale = LocalMontScale.current
    DesktopCard(width = 360, maxHeight = 380) {
        MontLabel("WINDOWS", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * scale))

        if (apps.isEmpty()) MontDetail("Nothing open.")

        apps.forEach { app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MontRow(label = app.label, modifier = Modifier.weight(1f)) { onFocus(app) }
                MontLabel(
                    "X",
                    Modifier
                        .combinedClickable { onClose(app) }
                        .padding(start = 8.dp * scale),
                    alpha = MontWhite.ACTIVE,
                    size = 15
                )
            }
        }

        Spacer(Modifier.height(10.dp * scale))
        MontRow(label = "Close", active = false) { onDismiss() }
    }
}

/**
 * A short list of the phone's own screens.
 *
 * What a right click on the bar opens. Everything on it is somewhere Android already has a screen
 * for — miniMont does not reimplement an alarm clock or a battery graph, it opens theirs on the
 * desktop as a window like anything else.
 */
@Composable
private fun MenuCard(
    title: String,
    rows: List<Pair<String, String>>,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    leading: @Composable (() -> Unit)? = null
) {
    val scale = LocalMontScale.current
    DesktopCard(width = 300, maxHeight = 260) {
        MontLabel(title, size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * scale))
        leading?.invoke()
        rows.forEach { (label, action) ->
            MontRow(label = label) { onOpen(action) }
        }
        Spacer(Modifier.height(10.dp * scale))
        MontRow(label = "Close", active = false) { onDismiss() }
    }
}

/**
 * The same scale the chrome is composed at, worked out from the same rule.
 *
 * Duplicated here rather than read from the composition local, because the area has to be computed
 * from the display's own dimensions and not from whatever surface it is drawn in.
 */
private fun scaleOf(configuration: android.content.res.Configuration): Float =
    (minOf(configuration.screenWidthDp, configuration.screenHeightDp) / 560f).coerceIn(1f, 1.6f)

/** One square of the grid. Equal width, centred, so four different things line up as four. */
@Composable
private fun Cell(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}

/**
 * Quick toggles, behind the battery.
 *
 * Two switches, one action and a way through to everything else. The switches are the two things
 * that change what the phone is doing while it is being a desktop — the radio it is streaming over,
 * and the mode that will throttle it — and both are read from the phone rather than remembered, so
 * the card is right even when something else changed them.
 *
 * The toggle says what the control currently is, never what pressing it would do.
 */
@Composable
private fun QuickCard(
    running: List<String>,
    onWifi: (Boolean) -> Unit,
    onBatterySaver: (Boolean) -> Unit,
    onCloseAll: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scale = LocalMontScale.current
    var reads by remember { mutableStateOf(0) }
    var wifi by remember { mutableStateOf(false) }
    var saver by remember { mutableStateOf(false) }

    // Read on open, and again a moment after anything is pressed: the host flips these through the
    // shell and the system takes its time agreeing, so an answer read immediately is the old one.
    LaunchedEffect(reads) {
        repeat(4) {
            wifi = wifiOn(context)
            saver = saverOn(context)
            kotlinx.coroutines.delay(600)
        }
    }

    DesktopCard(width = 320, maxHeight = 320) {
        MontLabel("QUICK", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * scale))

        ToggleRow("Wi-Fi", wifi) {
            wifi = it
            onWifi(it)
            reads++
        }
        ToggleRow("Battery saver", saver) {
            saver = it
            onBatterySaver(it)
            reads++
        }

        Spacer(Modifier.height(12.dp * scale))
        MontRow(label = "Close all windows", enabled = running.isNotEmpty()) { onCloseAll() }
        MontRow(label = "miniMont settings", active = false) { onSettings() }
        Spacer(Modifier.height(10.dp * scale))
        MontRow(label = "Close", active = false) { onClose() }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingToggle(label, checked, onChange)

private fun wifiOn(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
}.getOrDefault(false)

private fun saverOn(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
}.getOrDefault(false)

/**
 * The calendar, behind the clock.
 *
 * A black card and a white month. The grid is Light — the one place in miniMont where the lightest
 * weight does work, because a month is forty-two numbers and every one of them in Black is a wall.
 * The month, the year and today are Black against it, which is the whole hierarchy: what you came
 * to read is heavy, what it sits in is not.
 */
@Composable
private fun CalendarCard(onClose: () -> Unit) {
    val scale = LocalMontScale.current
    val today = remember { LocalDate.now() }
    val first = remember(today) { today.withDayOfMonth(1) }
    // Monday first, because the week number below is the ISO one and they have to agree.
    val offset = remember(first) { first.dayOfWeek.value - 1 }
    val length = remember(today) { today.lengthOfMonth() }
    val week = remember(today) { today.get(WeekFields.ISO.weekOfWeekBasedYear()) }

    DesktopCard(width = 300, maxHeight = 380) {
        MontLabel(
            first.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase(),
            size = 16,
            alpha = 1f
        )
        MontLabel("${today.year}", size = 16, alpha = MontWhite.DIM)
        Spacer(Modifier.height(12.dp * scale))

        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { day ->
                Light(day, Modifier.weight(1f), alpha = MontWhite.DETAIL, size = 10)
            }
        }
        Spacer(Modifier.height(4.dp * scale))

        var cell = 1 - offset
        while (cell <= length) {
            Row(Modifier.fillMaxWidth()) {
                repeat(7) {
                    val day = cell
                    if (day in 1..length) {
                        if (day == today.dayOfMonth) {
                            // Today is the bright one. No box, no ring, no fill — the same rule the
                            // dock, the rows and the chips already follow.
                            MontLabel(
                                "$day",
                                Modifier.weight(1f),
                                alpha = MontWhite.ACTIVE,
                                size = 13
                            )
                        } else {
                            Light("$day", Modifier.weight(1f), alpha = MontWhite.PRIMARY, size = 13)
                        }
                    } else {
                        Light("", Modifier.weight(1f), size = 13)
                    }
                    cell++
                }
            }
            Spacer(Modifier.height(6.dp * scale))
        }

        Spacer(Modifier.height(6.dp * scale))
        Light("WEEK $week", alpha = MontWhite.DETAIL, size = 11)
        Spacer(Modifier.height(10.dp * scale))
        MontRow(label = "Close", active = false) { onClose() }
    }
}

/**
 * The notification centre, down the right of the screen.
 *
 * Grouped by application, because that is how they arrive and how you deal with them — six from one
 * chat is one conversation, not six problems.
 *
 * Each one is a bar in the app's own colour with its title in white Mont Black and an X at the far
 * end, and touching the underside of that bar, a white box with what the notification actually
 * says. The inversion is deliberate: everywhere else in miniMont white type sits on black, and here
 * the content is the one thing that came from somewhere else, so it is given the opposite ground
 * and reads as quoted rather than as ours.
 */
@Composable
private fun NotificationsCard(onClose: () -> Unit) {
    val scale = LocalMontScale.current
    val notes by Notifications.notes.collectAsState()
    val ongoing by Notifications.ongoing.collectAsState()
    var showOngoing by remember { mutableStateOf(false) }
    val shown = if (showOngoing) ongoing else notes
    val grouped = remember(shown) { shown.groupBy { it.packageName } }

    // Capped like every other card and scrolling inside the cap, rather than running the height of
    // the display. What made it read as a sidebar was the height, not the side it was on.
    DesktopCard(width = 380, maxHeight = 420) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MontLabel(
                if (showOngoing) "RUNNING" else "NOTIFICATIONS",
                Modifier.weight(1f),
                size = 16,
                alpha = MontWhite.PRIMARY
            )
            // The count of things that are merely happening, in the corner. They cannot be
            // dismissed and they never reach zero, so they are not in the list and not in the
            // number on the taskbar — they are here, one press away, for when you do want to know
            // what is running.
            if (ongoing.isNotEmpty()) {
                MontLabel(
                    "${ongoing.size}",
                    Modifier
                        .combinedClickable { showOngoing = !showOngoing }
                        .padding(start = 8.dp * scale),
                    size = 13,
                    alpha = if (showOngoing) MontWhite.ACTIVE else MontWhite.DIM
                )
            }
        }
        Spacer(Modifier.height(10.dp * scale))

        if (shown.isEmpty()) {
            MontDetail(if (showOngoing) "Nothing running." else "Nothing standing.")
        }

        grouped.forEach { (_, group) ->
            // The app is named once, over its own run, rather than on every card belonging to it.
            MontLabel(group.first().app.uppercase(), size = 11, alpha = MontWhite.DETAIL)
            Spacer(Modifier.height(4.dp * scale))
            group.forEach { note ->
                NoteCard(note)
                Spacer(Modifier.height(9.dp * scale))
            }
            Spacer(Modifier.height(4.dp * scale))
        }

        Spacer(Modifier.height(6.dp * scale))
        if (showOngoing) {
            MontRow(label = "Back to notifications") { showOngoing = false }
        } else if (notes.isNotEmpty()) {
            MontRow(label = "Clear all") { Notifications.dismissAll() }
        }
        MontRow(label = "Close", active = false) { onClose() }
    }
}

/** The title's size, and the measure everything else in a notification is set against. */
private const val NOTE_TITLE = 13

/** What the notification said, in the black that everything on this white ground is written in. */
@Composable
private fun NoteText(text: String, modifier: Modifier = Modifier) {
    val scale = LocalMontScale.current
    Text(
        text,
        modifier = modifier,
        color = Color.Black,
        fontFamily = Mont,
        fontWeight = FontWeight.Normal,
        fontSize = (12 * scale).sp,
        lineHeight = (16 * scale).sp
    )
}

/** How wide one application is in a SuperFill grid, icon and name and the air around them. */
private const val CELL = 84

/** One notification: its own bar, and the white box under it holding what it said. */
@Composable
private fun NoteCard(note: Note) {
    val scale = LocalMontScale.current

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(note.colour))
                .combinedClickable(enabled = note.openable) { Notifications.open(note.key) }
                .padding(horizontal = 8.dp * scale, vertical = 5.dp * scale),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MontLabel(
                note.title.ifBlank { note.app }.uppercase(),
                Modifier.weight(1f),
                size = NOTE_TITLE,
                alpha = MontWhite.ACTIVE
            )
            // Nothing to close on something that is merely happening: the X is not drawn faint,
            // it is not drawn.
            if (note.dismissable) {
                MontLabel(
                    "X",
                    Modifier
                        // Padded before it is clickable, so the padding is part of the target
                        // rather than a margin around one. A letter is a small thing to hit.
                        .padding(horizontal = 8.dp * scale, vertical = 4.dp * scale)
                        .combinedClickable { Notifications.dismiss(note.key) },
                    size = NOTE_TITLE,
                    alpha = MontWhite.ACTIVE
                )
            }
        }

        // Touching the bar above it. No gap, no rounding, no shadow — one object in two halves.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 8.dp * scale, vertical = 7.dp * scale)
        ) {
            // A picture sits beside the words, not over them, and is capped at three times the
            // title. Left to itself a notification's image is a full-width photograph, and a
            // conversation of them becomes a gallery you have to scroll past to read anything.
            val picture = note.picture
            if (picture != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Image(
                        picture,
                        contentDescription = null,
                        modifier = Modifier
                            .height((NOTE_TITLE * 3).dp * scale)
                            .widthIn(max = (NOTE_TITLE * 6).dp * scale),
                        contentScale = ContentScale.Fit
                    )
                    if (note.text.isNotBlank()) {
                        Spacer(Modifier.width(8.dp * scale))
                        NoteText(note.text, Modifier.weight(1f))
                    }
                }
            } else if (note.text.isNotBlank()) {
                NoteText(note.text)
            }

            // Whatever the notification itself offered, at the bottom right of its own box, in the
            // black that everything on this ground is written in.
            if (note.actions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp * scale))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp * scale, Alignment.End)
                ) {
                    note.actions.forEach { action ->
                        Text(
                            action.title.uppercase(),
                            modifier = Modifier.combinedClickable {
                                Notifications.act(note.key, action.index)
                            },
                            color = Color.Black,
                            fontFamily = Mont,
                            fontWeight = FontWeight.Black,
                            fontSize = (11 * scale).sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** Mont Light, for the one place a month's worth of numbers has to sit behind what matters. */
@Composable
private fun Light(
    text: String,
    modifier: Modifier = Modifier,
    alpha: Float = MontWhite.PRIMARY,
    size: Int = 12
) {
    Text(
        text,
        modifier = modifier,
        color = Color.White.copy(alpha = alpha),
        fontFamily = Mont,
        fontWeight = FontWeight.Light,
        fontSize = (size * LocalMontScale.current).sp,
        maxLines = 1
    )
}

private fun batteryLevel(context: Context): Int =
    runCatching {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(0)
