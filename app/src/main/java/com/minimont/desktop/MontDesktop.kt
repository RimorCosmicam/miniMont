@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.minimont.desktop

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import com.minimont.ui.mont.LocalMontScale
import com.minimont.ui.mont.Mont
import com.minimont.ui.mont.MontAccent
import com.minimont.ui.mont.MontCard
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
private const val ICON = 30

/**
 * The taskbar's padding, on every side, and the gap above it.
 *
 * Half what the dock used to carry. A dock floats and needs air around it to read as an object; a
 * bar is the edge of the screen and needs only enough room not to crowd what is standing in it.
 */
private const val BAR = 7

/** What the chrome can have open above the dock. At most one, because two is a window manager. */
private enum class Panel {
    NONE, APPS, SETTINGS, ITEM, CALENDAR, NOTIFICATIONS, QUICK, WINDOWS,
    CLOCK_MENU, BATTERY_MENU, NOTIFICATION_MENU
}

/** The phone's own screens, named by what they do rather than by which class does them. */
private object Phone {
    const val ALARMS = "android.intent.action.SHOW_ALARMS"
    const val DATE_TIME = "android.settings.DATE_SETTINGS"
    const val BATTERY = "android.intent.action.POWER_USAGE_SUMMARY"
    const val BATTERY_SAVER = "android.settings.BATTERY_SAVER_SETTINGS"
    const val NOTIFICATIONS = "android.settings.NOTIFICATION_SETTINGS"
}

/**
 * A second-button press, and the long press that stands in for it.
 *
 * The desktop has a real mouse now, so the taskbar answers a right click the way everything else on
 * a desktop does. It also answers a long press, because the tablet at the other end has fingers and
 * no second button, and an action that exists only for one of the two input devices is an action
 * half the users cannot reach.
 */
private fun Modifier.secondary(onClick: () -> Unit): Modifier = this.pointerInput(onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onClick()
            }
        }
    }
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
    onBack: () -> Unit,
    onHome: () -> Unit
) {
    val context = LocalContext.current
    val store by DesktopStore.state.collectAsState()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var selected by remember { mutableStateOf<DesktopApp?>(null) }

    val apps = remember { AppCatalog.apps(context) }

    // The area windows are allowed to open in: the display, less the taskbar and its own padding on
    // every other side. Measured rather than assumed — the bar's height is whatever is standing in
    // it — and told to the host, which draws none of this and cannot work it out.
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var bar by remember { mutableStateOf(0) }
    LaunchedEffect(bar, configuration) {
        if (bar == 0) return@LaunchedEffect
        with(density) {
            val inset = (BAR.dp * scaleOf(configuration)).roundToPx()
            val width = configuration.screenWidthDp.dp.roundToPx()
            val height = configuration.screenHeightDp.dp.roundToPx()
            onArea(inset, inset, width - inset, height - bar - inset)
        }
    }

    // Pinned first, in the order they were pinned, then anything running that is not pinned. A dock
    // whose icons move as apps open is a dock you have to read before you can aim at it.
    val docked = remember(store.pinned, running, apps) {
        val byPackage = apps.associateBy { it.packageName }
        (store.pinned + running.filterNot { it in store.pinned }).mapNotNull { byPackage[it] }
    }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        when (panel) {
            Panel.APPS -> StartMenu(
                apps = apps,
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

            Panel.WINDOWS -> WindowsCard(
                apps = apps.filter { it.packageName in running },
                onFocus = { app ->
                    panel = Panel.NONE
                    onLaunch(app.component)
                },
                onClose = { app -> onClose(app.packageName) },
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

        Spacer(Modifier.height(BAR.dp * LocalMontScale.current))

        Taskbar(
            apps = docked,
            running = running,
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
            onNotificationMenu = { panel = Panel.NOTIFICATION_MENU }
        )
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
            .heightIn(max = maxHeight.dp * scale),
        content = content
    )
}

@Composable
private fun StartMenu(
    apps: List<DesktopApp>,
    onOpen: (DesktopApp) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit
) {
    DesktopCard {
        MontLabel("APPS", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * LocalMontScale.current))
        apps.forEach { app ->
            MontRow(label = app.label) { onOpen(app) }
        }
        Spacer(Modifier.height(10.dp * LocalMontScale.current))
        MontRow(label = "Settings", active = false) { onSettings() }
        MontRow(label = "Close", active = false) { onClose() }
    }
}

/**
 * Settings, as a card.
 *
 * On a phone this would open hard against the top of the screen with no header, because a panel
 * over the thing it edits is unmistakably about it. A card floating in the middle of a wallpaper is
 * about nothing until it says so, which is why this is the one titled surface in the language.
 */
@Composable
private fun SettingsCard(
    state: DesktopStore.State,
    notifications: Boolean,
    onPickImage: () -> Unit,
    onGrantNotifications: () -> Unit,
    onClose: () -> Unit
) {
    DesktopCard {
        MontLabel("SETTINGS", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * LocalMontScale.current))
        MontLabel("WALLPAPER", size = 11, alpha = MontWhite.DETAIL)
        Spacer(Modifier.height(6.dp * LocalMontScale.current))
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
        if (!notifications) {
            Spacer(Modifier.height(14.dp * LocalMontScale.current))
            MontLabel("STATUS", size = 11, alpha = MontWhite.DETAIL)
            Spacer(Modifier.height(6.dp * LocalMontScale.current))
            // Asked for here rather than at the door, because the desktop works without it and a
            // permission demanded before anything has been shown is a permission nobody grants.
            MontRow(label = "Show the notification count", active = false) { onGrantNotifications() }
        }
        Spacer(Modifier.height(10.dp * LocalMontScale.current))
        MontRow(label = "Close", active = false) { onClose() }
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
    onDismiss: () -> Unit
) {
    DesktopCard(width = 300, maxHeight = 220) {
        MontLabel(app.label.uppercase(), size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * LocalMontScale.current))
        MontRow(label = if (pinned) "Unpin from the taskbar" else "Pin to the taskbar") {
            onPin()
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
    onNotificationMenu: () -> Unit
) {
    val scale = LocalMontScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(MontSurface)
            .onSizeChanged { onMeasured(it.height) }
            .padding(BAR.dp * scale)
    ) {
        Navigation(
            Modifier.align(Alignment.CenterStart),
            onBack = onBack,
            onHome = onHome,
            onWindows = onWindows
        )
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(9.dp * scale),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StartSquares(Modifier.size(ICON.dp * scale), onStart)
            apps.forEach { app ->
                DockItem(app, app.packageName in running, { onOpen(app) }, { onHold(app) })
            }
        }
        StatusBlock(
            Modifier.align(Alignment.CenterEnd),
            onClock = onClock,
            onClockMenu = onClockMenu,
            onBattery = onBattery,
            onBatteryMenu = onBatteryMenu,
            onNotifications = onNotifications,
            onNotificationMenu = onNotificationMenu
        )
    }
}

/**
 * Back, home and windows, at the left.
 *
 * All three mean something *inside* miniMont and nothing outside it. Home shows the desktop rather
 * than leaving for the phone's launcher — leaving would end the thing you are using. Windows lists
 * what is open here, not what Android has in its own recents. Back is the only one that is what it
 * looks like: it goes back in whatever has focus.
 *
 * Words rather than a triangle, a circle and a square. Mont says a thing with a word when a word
 * will do, and these three have been three shapes for fifteen years without anybody agreeing which
 * shape is which.
 */
@Composable
private fun Navigation(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onWindows: () -> Unit
) {
    val scale = LocalMontScale.current
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp * scale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MontLabel("BACK", Modifier.combinedClickable(onClick = onBack), alpha = MontWhite.DIM, size = 11)
        MontLabel("HOME", Modifier.combinedClickable(onClick = onHome), alpha = MontWhite.DIM, size = 11)
        MontLabel("WINDOWS", Modifier.combinedClickable(onClick = onWindows), alpha = MontWhite.DIM, size = 11)
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

/** An application in the dock. Running is simply the bright one. */
@Composable
private fun DockItem(
    app: DesktopApp,
    running: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit
) {
    val scale = LocalMontScale.current
    Box(
        Modifier
            .size(ICON.dp * scale)
            .combinedClickable(onClick = onClick, onLongClick = onHold),
        contentAlignment = Alignment.Center
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                icon,
                contentDescription = app.label,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (running) MontWhite.ACTIVE else MontWhite.DIM)
            )
        } else {
            MontLabel(
                app.label.take(1).uppercase(),
                size = 20,
                alpha = if (running) MontWhite.ACTIVE else MontWhite.DIM
            )
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

    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp * scale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Nothing announces itself: with nothing waiting there is no bubble, not an empty one.
        if (notes.isNotEmpty()) {
            Bubble(
                count = notes.size,
                modifier = Modifier
                    .secondary(onNotificationMenu)
                    .combinedClickable(
                        onClick = onNotifications,
                        onLongClick = onNotificationMenu
                    )
            )
        }

        Row(
            Modifier
                .secondary(onBatteryMenu)
                .combinedClickable(onClick = onBattery, onLongClick = onBatteryMenu),
            horizontalArrangement = Arrangement.spacedBy(5.dp * scale),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MontLabel("$battery%", size = 13, colour = level, alpha = 1f)
            BatteryMark(battery, level, Modifier.size(width = 22.dp * scale, height = 11.dp * scale))
        }

        Column(
            Modifier
                .secondary(onClockMenu)
                .combinedClickable(onClick = onClock, onLongClick = onClockMenu),
            horizontalAlignment = Alignment.End
        ) {
            MontLabel(time, size = 16, alpha = MontWhite.PRIMARY)
            MontLabel(date, size = 10, alpha = MontWhite.DETAIL)
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
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp * LocalMontScale.current),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MontLabel(label.uppercase(), Modifier.weight(1f), alpha = MontWhite.ACTIVE)
        MontToggle(checked, onChange)
    }
}

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

/** What is standing, and the two things that can be done to it. */
@Composable
private fun NotificationsCard(onClose: () -> Unit) {
    val scale = LocalMontScale.current
    val notes by Notifications.notes.collectAsState()

    DesktopCard(width = 380, maxHeight = 420) {
        MontLabel("NOTIFICATIONS", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * scale))

        if (notes.isEmpty()) {
            Light("Nothing standing.", alpha = MontWhite.DETAIL, size = 12)
        }

        notes.forEach { note ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp * scale),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    MontLabel(
                        note.title.ifBlank { note.app }.uppercase(),
                        alpha = MontWhite.PRIMARY,
                        size = 13
                    )
                    if (note.text.isNotBlank()) {
                        Light(note.text, alpha = MontWhite.DETAIL, size = 11)
                    }
                }
                // Two letters rather than two icons, because Mont says a thing with a word when a
                // word will do, and these are the shortest words there are.
                MontLabel(
                    "O",
                    Modifier
                        .combinedClickable(enabled = note.openable) { Notifications.open(note.key) }
                        .padding(horizontal = 8.dp * scale),
                    alpha = if (note.openable) MontWhite.ACTIVE else MontWhite.DISABLED,
                    size = 15
                )
                MontLabel(
                    "X",
                    Modifier
                        .combinedClickable { Notifications.dismiss(note.key) }
                        .padding(start = 4.dp * scale),
                    alpha = MontWhite.ACTIVE,
                    size = 15
                )
            }
        }

        Spacer(Modifier.height(10.dp * scale))
        MontRow(label = "Close", active = false) { onClose() }
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
