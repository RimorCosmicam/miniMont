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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.minimont.ui.mont.LocalMontScale
import com.minimont.ui.mont.Mont
import com.minimont.ui.mont.MontAccent
import com.minimont.ui.mont.MontCard
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
private const val PAD = 14

/** What the chrome can have open above the dock. At most one, because two is a window manager. */
private enum class Panel { NONE, APPS, SETTINGS, ITEM, CALENDAR, NOTIFICATIONS, QUICK }

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
    onBatterySaver: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val store by DesktopStore.state.collectAsState()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var selected by remember { mutableStateOf<DesktopApp?>(null) }

    val apps = remember { AppCatalog.apps(context) }

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

            Panel.CALENDAR -> CalendarCard { panel = Panel.NONE }

            Panel.NOTIFICATIONS -> NotificationsCard { panel = Panel.NONE }

            Panel.NONE -> Unit
        }

        Spacer(Modifier.height(14.dp * LocalMontScale.current))

        Row(verticalAlignment = Alignment.Bottom) {
            Dock(
                apps = docked,
                running = running,
                onStart = { panel = if (panel == Panel.APPS) Panel.NONE else Panel.APPS },
                // Launching an app that is already open brings its window forward, which is
                // what clicking a dock icon means in every desktop anybody has used.
                onOpen = { app ->
                    panel = Panel.NONE
                    onLaunch(app.component)
                },
                onHold = { app ->
                    selected = app
                    panel = Panel.ITEM
                }
            )
            Spacer(Modifier.width(14.dp * LocalMontScale.current))
            StatusCard(
                onClock = { panel = if (panel == Panel.CALENDAR) Panel.NONE else Panel.CALENDAR },
                onBattery = { panel = if (panel == Panel.QUICK) Panel.NONE else Panel.QUICK },
                onNotifications = {
                    panel = if (panel == Panel.NOTIFICATIONS) Panel.NONE else Panel.NOTIFICATIONS
                }
            )
        }

        // Half the padding it used to have: the dock sits closer to the edge it belongs to, and
        // what is left reads as a margin rather than as a gap.
        Spacer(Modifier.height((PAD / 2).dp * LocalMontScale.current))
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
    onDismiss: () -> Unit
) {
    DesktopCard(width = 300, maxHeight = 220) {
        MontLabel(app.label.uppercase(), size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(10.dp * LocalMontScale.current))
        MontRow(label = if (pinned) "Unpin from the dock" else "Pin to the dock") {
            onPin()
            onDismiss()
        }
        // Closing means the program is gone, not backgrounded. A window that reopens with the state
        // you closed it in is a window that did not close.
        MontRow(label = "Close", enabled = open) { onClose() }
        Spacer(Modifier.height(10.dp * LocalMontScale.current))
        MontRow(label = "Cancel", active = false) { onDismiss() }
    }
}

@Composable
private fun Dock(
    apps: List<DesktopApp>,
    running: List<String>,
    onStart: () -> Unit,
    onOpen: (DesktopApp) -> Unit,
    onHold: (DesktopApp) -> Unit
) {
    val scale = LocalMontScale.current
    Row(
        Modifier
            .background(MontSurface)
            .padding(PAD.dp * scale),
        horizontalArrangement = Arrangement.spacedBy(9.dp * scale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StartSquares(Modifier.size(ICON.dp * scale), onStart)
        apps.forEach { app ->
            DockItem(app, app.packageName in running, { onOpen(app) }, { onHold(app) })
        }
    }
}

/**
 * The first thing in the dock: a four by four grid of mustard squares.
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
 * The clock block: four facts in a two by two grid.
 *
 * Hours and minutes on the top row, and under each of them the number that belongs there — the
 * battery under the hours, what is waiting under the minutes. It reads as a grid rather than as a
 * clock with a caption, which is what it is: four separate things, three of which you glance at and
 * one of which you read.
 *
 * The battery is green because it is the one number here that is about the phone rather than about
 * the time, and red under twenty, which is the only place red appears in miniMont.
 */
@Composable
private fun StatusCard(
    onClock: () -> Unit,
    onBattery: () -> Unit,
    onNotifications: () -> Unit
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

    val hours = remember(now) { SimpleDateFormat("HH", Locale.getDefault()).format(now) }
    val minutes = remember(now) { SimpleDateFormat("mm", Locale.getDefault()).format(now) }

    Column(
        Modifier
            .background(MontSurface)
            // As tall as the dock and as wide as it is tall, so the two stand together rather than
            // each following a rule of its own.
            .size((PAD * 2 + ICON).dp * scale)
            .padding(horizontal = 5.dp * scale, vertical = 5.dp * scale),
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth()) {
            Cell(Modifier.weight(1f).combinedClickable(onClick = onClock)) {
                MontLabel(hours, size = 16, alpha = MontWhite.PRIMARY)
            }
            Cell(Modifier.weight(1f).combinedClickable(onClick = onClock)) {
                MontLabel(minutes, size = 16, alpha = MontWhite.PRIMARY)
            }
        }
        Spacer(Modifier.height(2.dp * scale))
        Row(Modifier.fillMaxWidth()) {
            Cell(Modifier.weight(1f).combinedClickable(onClick = onBattery)) {
                MontLabel(
                    "$battery",
                    size = 10,
                    colour = if (battery in 1..19) MontAccent.LowBattery else MontAccent.Live,
                    alpha = 1f
                )
            }
            Cell(Modifier.weight(1f).combinedClickable(onClick = onNotifications)) {
                // Nothing announces itself: at zero the count is not drawn faintly, and the block
                // it lives in is not drawn either. The cell is simply empty.
                if (notes.isNotEmpty()) {
                    Box(
                        Modifier
                            .background(Color.White)
                            .padding(horizontal = 3.dp * scale, vertical = 1.dp * scale)
                    ) {
                        Text(
                            "${notes.size}",
                            color = Color.Black,
                            fontFamily = Mont,
                            fontWeight = FontWeight.Black,
                            fontSize = (9 * scale).sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

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
