package com.minimont

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.minimont.adb.AdbClient
import com.minimont.adb.AdbMdns
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where a session has got to.
 *
 * Named states rather than a pile of booleans, because "no picture" has half a dozen causes that
 * look identical from the outside — not paired, paired but not connected, connected but the host
 * would not start, started but the desktop has not appeared — and each of them wants a different
 * sentence in front of the user.
 */
/**
 * The sizes a desktop is actually run at.
 *
 * Not derived from the tablet's panel. Scaling the panel's own shape produces sizes like
 * 1808 x 1088 — inside every limit the decoder states, and decoded as a black screen with
 * fragments over it. Hardware agrees on the sizes everybody uses and quietly disagrees on the
 * ones nobody does, so the list is the ordinary ones and the picture is letterboxed when the
 * tablet is a different shape.
 */
private val LADDER = listOf(
    1920 to 1080,
    1600 to 900,
    1280 to 800,
    1280 to 720,
    1024 to 768
)

enum class DesktopStage {
    IDLE,
    PAIRING,
    CONNECTING,
    CONNECTED,
    STARTING,
    RUNNING,
    FAILED
}

data class DesktopState(
    val stage: DesktopStage = DesktopStage.IDLE,
    val message: String = "",
    val displayId: Int? = null,
    val pairingPort: Int? = null,
    val connectPort: Int? = null,
    /** The size actually running, as the host reported it — never the one merely asked for. */
    val size: Pair<Int, Int>? = null,
    /** The AirMate client receiving the picture, once one has said hello. */
    val client: String? = null,
    /** The client's own panel, once it has said. */
    val panel: Pair<Int, Int>? = null,
    /** The largest frame the client's decoder will accept, once it has said. */
    val ceiling: Pair<Int, Int>? = null,
    val log: List<String> = emptyList(),
    /**
     * The packages the desktop has open, newest last, as the host reports them.
     *
     * Not `running`: that word is already taken here by whether the desktop itself is up, and the
     * two are different questions with the same answer often enough to be worth keeping apart.
     */
    val openApps: List<String> = emptyList(),
    /** What is on each virtual desktop, in order, and which one is showing. */
    val desktops: List<List<String>> = listOf(emptyList()),
    val desktop: Int = 0,
    /** Whether the next click has been asked to be a right click. */
    val armed: Boolean = false,
    /** Where the last screenshot was saved, or null if the last one did not save. */
    val shot: String? = null,
    /** When the last screenshot came back, so the button can say so without saying anything. */
    val shotAt: Long = 0L
) {
    /** The sizes worth offering: the ordinary ones, minus anything past the client's decoder. */
    val choices: List<Pair<Int, Int>>
        get() {
            val limit = ceiling ?: return LADDER
            return LADDER.filter { it.first <= limit.first && it.second <= limit.second }
                .ifEmpty { listOf(LADDER.last()) }
        }

    val running: Boolean get() = stage == DesktopStage.RUNNING
    val busy: Boolean
        get() = stage == DesktopStage.PAIRING || stage == DesktopStage.CONNECTING || stage == DesktopStage.STARTING
}

/**
 * The whole session, from a six-digit code to a desktop on the tablet.
 *
 * The sequence never changes: pair once, connect, launch the host as the shell user, and then watch
 * what it says. The host itself is the code in `server/`, compiled into this same APK — which is
 * why launching it needs no file to be pushed anywhere, only this APK's own path handed to
 * `app_process` as a classpath.
 */
class DesktopController private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = AdbClient(context)
    val mdns = AdbMdns(context)

    private val _state = MutableStateFlow(DesktopState())
    val state = _state.asStateFlow()

    private var stream: AdbStream? = null

    /**
     * The display the person is looking at — the cover screen on a folded Flip.
     *
     * Set by the window that has one. Anything the desktop asks for that is really a phone job, the
     * file picker above all, has to be sent there by name: Android's default is the inner panel,
     * which on a folded phone is switched off.
     */
    @Volatile
    var phoneDisplayId: Int = android.view.Display.DEFAULT_DISPLAY

    /**
     * Whether One UI is invited to put its own desktop on the display.
     *
     * Off is what miniMont is. It is a switch rather than a constant because whether a display
     * without system decorations will host a window at all is the one thing this whole design rests
     * on and has not yet been measured on a real phone — and finding out must not need a rebuild.
     */
    @Volatile
    var decorations: Boolean = false

    init {
        mdns.start()
        scope.launch {
            mdns.pairingPort.collect { port -> _state.update { it.copy(pairingPort = port) } }
        }
        scope.launch {
            mdns.connectPort.collect { port -> _state.update { it.copy(connectPort = port) } }
        }
    }

    private val _paired = MutableStateFlow(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAIRED, false)
    )

    /**
     * Whether adbd is believed to have our key — watched, not sampled.
     *
     * Wireless debugging comes back off after a restart, and a remembered pairing then describes a
     * phone that no longer exists. Onboarding asks this, so it has to be able to change its mind
     * while somebody is looking at it rather than only when the screen is next resumed.
     */
    val pairing = _paired.asStateFlow()

    /** True once adbd has our key, so a later start needs no code. */
    val paired: Boolean get() = _paired.value

    private fun keepPairing(paired: Boolean) {
        _paired.value = paired
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PAIRED, paired).apply()
    }

    /**
     * @param onPaired run the moment the code is accepted, before the desktop is started.
     *
     * Pairing happens in Android's settings, which means the user is looking at Settings when it
     * succeeds. Something has to bring them back, and it has to happen at the success itself rather
     * than when the desktop finishes starting — that is seconds later, and until then the screen
     * they are looking at gives no sign that the thing they typed worked.
     */
    fun pair(port: Int, code: String, onPaired: () -> Unit = {}) {
        scope.launch {
            _state.update { it.copy(stage = DesktopStage.PAIRING, message = "Pairing…") }
            val host = mdns.host.value
            client.pairWith(host, port, code)
                .onSuccess {
                    keepPairing(true)
                    mdns.forgetPairingPort()
                    note("paired with $host:$port")
                    onPaired()
                    start()
                }
                .onFailure { failure ->
                    fail("Pairing was refused. Check the code and try again.", failure)
                }
        }
    }

    /**
     * Bring the desktop up, pairing permitting.
     *
     * Connecting and launching are one action from the user's point of view — nobody wants to be
     * told the socket is open, they want the screen — so a failure anywhere in here reports the
     * step that failed rather than the step they asked for.
     */
    /**
     * @param density why the desktop stopped looking like a magnifying glass held the wrong way.
     *
     * 160 means one dp is one pixel, so everything the *system* draws — widgets, dialogs, an app's
     * own text — comes out at half the size of anything miniMont draws, because miniMont multiplies
     * its own figures by the Mont scale and nobody else can. 240 is a real density bucket, so apps
     * pick their hdpi resources rather than being stretched, and the Mont scale falls to about one
     * on its own: the display is 1067 by 600 dp instead of 1600 by 900, and the scale is worked out
     * from that. One lever, and both halves of the screen agree afterwards.
     */
    fun start(
        width: Int = 1920,
        height: Int = 1080,
        density: Int = com.minimont.desktop.DesktopStore.state.value.density
    ) {
        scope.launch {
            halt()
            val port = _state.value.connectPort
            _cursor.value = 0f to 0f
            _state.update { it.copy(stage = DesktopStage.CONNECTING, message = "Connecting…") }
            val host = mdns.host.value

            // Both doors, in order. mDNS announces the wireless-debugging port and that is the one
            // pairing authorised; 5555 is there whenever somebody has turned on adb over TCP, and
            // it accepts the same key. Trying the second costs one refused socket and saves a
            // six-digit code that was never needed.
            //
            // Nothing announced is not nothing listening: adb over TCP does not advertise itself,
            // so refusing to try 5555 because mDNS was quiet meant a button that did nothing on a
            // phone with a door standing open.
            var failure: Throwable? = null
            val connected = listOfNotNull(port, LEGACY_PORT).distinct().any { candidate ->
                client.connectTo(host, candidate)
                    .onFailure { failure = it }
                    .isSuccess
            }
            if (!connected) {
                // Whatever was remembered, it is not true now: nothing answered on either port.
                // Saying so puts the requirement back on the onboarding screen, which is the only
                // place that explains how to switch it on again.
                keepPairing(false)
                fail(
                    "Wireless debugging is off — it goes off by itself after a restart. " +
                        "Switch it back on in Developer options and pair again.",
                    failure
                )
                return@launch
            }
            // adbd is the authority on whether it has our key, not a preference we wrote down. A
            // connection that succeeds *is* the pairing, however it was arrived at.
            keepPairing(true)
            _state.update { it.copy(stage = DesktopStage.CONNECTED, message = "Connected") }
            launchHost(width, height, density)
        }
    }

    private suspend fun launchHost(width: Int, height: Int, density: Int) {
        _state.update { it.copy(stage = DesktopStage.STARTING, message = "Starting the desktop…") }
        runCatching {
            // The host is a class in this very APK, so the classpath is our own installed path.
            // Nothing is written to /data/local/tmp: there is one artifact to install and update,
            // and no writable copy of our own code for anything else on the device to replace.
            val apk = context.applicationInfo.sourceDir
            val entry = com.minimont.server.Server::class.java.name
            val command = "export CLASSPATH='$apk'; " +
                "exec /system/bin/app_process /system/bin '$entry' " +
                "size=${width}x$height dpi=$density " +
                // The two that make this miniMont rather than AirMate: no system decorations, so
                // One UI puts nothing here, and our own backdrop as the first thing on the display.
                "decor=$decorations backdrop=$BACKDROP"
            Log.i(TAG, command)
            val opened = withContext(Dispatchers.IO) { client.openShell(command) }
            stream = opened
            watch(opened)
        }.onFailure { failure ->
            fail("The desktop host would not start.", failure)
        }
    }

    /**
     * Read what the host says, and let it say what state we are in.
     *
     * The host prints a line per stage, so the interface does not have to guess: the display id and
     * the moment the session is ready both arrive as text on this stream. It is also how we notice
     * the host dying — the stream ends.
     */
    private fun watch(opened: AdbStream) {
        scope.launch {
            runCatching {
                opened.openInputStream().bufferedReader().forEachLine { line ->
                    note(line)
                    when {
                        line.contains("Display created, displayId = ") -> {
                            val id = line.substringAfterLast("= ").trim().toIntOrNull()
                            _state.update { it.copy(displayId = id) }
                        }

                        // What is running, not what was asked for. The host refits itself when the
                        // client cannot decode the size we chose, so the two diverge routinely.
                        line.contains("Resolution = ") -> {
                            val size = parseSize(line.substringAfter("Resolution = "))
                            if (size != null) {
                                // The host puts its pointer in the middle of a new display, because
                                // a cursor that appears in the corner looks like one that failed.
                                // This end has to start from the same place or the drawn cursor and
                                // the real one disagree from the first movement.
                                _cursor.value = size.first / 2f to size.second / 2f
                                _state.update { it.copy(size = size) }
                            }
                        }

                        line.contains("client panel ") -> {
                            val panel = parseSize(line.substringAfter("client panel "))
                            val ceiling = parseSize(line.substringAfter("decoder ceiling "))
                            _state.update { it.copy(panel = panel, ceiling = ceiling) }
                        }

                        // The tablet arriving is what turns onboarding's second phase into its
                        // third, so it is watched for rather than asked about.
                        line.contains("Client paired:") -> {
                            val address = line.substringAfter("Client paired:").trim()
                            _state.update { it.copy(client = address) }
                        }

                        line.contains("BACKDROP FAILED") -> _state.update {
                            it.copy(
                                stage = DesktopStage.FAILED,
                                message = "The display would not accept a window. " +
                                    "Try it with One UI's own decorations switched on."
                            )
                        }
                        line.startsWith("[EVENT] shot") -> {
                            val path = line.substringAfter("shot").trim()
                            _cursorHidden.value = false
                            val saved = path.isNotEmpty() && path != "-"
                            if (saved) {
                                // The host has no Context to tell the scanner with, so the file
                                // would sit there unseen by the gallery until something else looked.
                                MediaScannerConnection.scanFile(
                                    context, arrayOf(path), arrayOf("image/png"), null
                                )
                            }
                            _state.update {
                                it.copy(
                                    shot = if (saved) path else null,
                                    shotAt = System.currentTimeMillis()
                                )
                            }
                        }

                        line.startsWith("[EVENT] armed") -> {
                            val on = line.trim().endsWith("1")
                            _state.update { it.copy(armed = on) }
                        }

                        line.startsWith("[EVENT] desks") -> {
                            val rest = line.substringAfter("desks").trim()
                            val current = rest.substringBefore(' ').toIntOrNull() ?: 0
                            val fields = rest.substringAfter(' ', "").split('|')
                            _state.update { state ->
                                state.copy(
                                    desktop = current,
                                    desktops = fields.map { field ->
                                        field.split(',').filter { it.isNotBlank() }
                                    }
                                )
                            }
                        }

                        line.startsWith("[EVENT] running") -> {
                            val packages = line.substringAfter("running").trim()
                                .split(',').filter { it.isNotBlank() }
                            _state.update { it.copy(openApps = packages) }
                        }

                        line.contains("SESSION READY") -> _state.update {
                            it.copy(stage = DesktopStage.RUNNING, message = "Desktop running")
                        }
                    }
                }
            }
            // The stream ended: either we closed it, or the host went away underneath us.
            if (_state.value.stage != DesktopStage.IDLE) {
                _state.update {
                    it.copy(stage = DesktopStage.IDLE, message = "Desktop stopped", displayId = null)
                }
            }
        }
    }

    /**
     * Say something to the host.
     *
     * The same ADB shell stream the host prints its log on, in the other direction. It is already
     * open and already authorised by the pairing, so four verbs need no second socket and no second
     * answer to the question of who may speak on it.
     */
    private fun send(line: String) {
        outgoing.trySend(line)
    }

    /**
     * One writer, one queue.
     *
     * Launching a coroutine per line was fine for four verbs a human presses. The pointer sends at
     * the rate a finger moves, and a coroutine per motion event is both wasteful and unordered —
     * two moves that overtake each other put the cursor somewhere neither of them meant.
     *
     * The queue drops rather than blocks. A pointer delta that could not be written for a whole
     * second is a delta whose moment has passed, and catching up on stale movement afterwards is
     * worse than having missed it.
     */
    private val outgoing = Channel<String>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            for (line in outgoing) {
                val open = stream ?: continue
                runCatching {
                    val out = open.openOutputStream()
                    out.write((line + "\n").toByteArray())
                    out.flush()
                }.onFailure { Log.e(TAG, "could not send: $line", it) }
            }
        }
    }

    /**
     * Where the cursor is, so something can draw it.
     *
     * The framework does not draw a pointer for injected events — the sprite on a real display is
     * driven by the input reader from real hardware, and nothing we inject reaches it. Clicks land
     * regardless, which is why the desktop worked and was impossible to aim. So miniMont keeps the
     * position it has been sending and draws the cursor itself.
     *
     * Kept here rather than asked of the host: this end already knows every delta it sent, and a
     * round trip per motion event to be told what we just said would be a cursor that lags the
     * finger by a network.
     */
    private val _cursor = MutableStateFlow(0f to 0f)
    val cursor = _cursor.asStateFlow()

    /** Whether the drawn cursor should stand out of the way — true only while a shot is taken. */
    private val _cursorHidden = MutableStateFlow(false)
    val cursorHidden = _cursorHidden.asStateFlow()

    /**
     * The desktop, saved as a picture.
     *
     * The pointer goes first. It is a window on this display like everything else, so it would
     * otherwise be in the file, and a screenshot with the mouse in it is a screenshot of the mouse.
     * It comes back when the host says the file is written, or shortly after regardless: an
     * invisible cursor is a worse failure than a cursor in a picture.
     */
    fun screenshot() {
        scope.launch {
            _cursorHidden.value = true
            delay(160)
            send("shot")
            delay(1500)
            _cursorHidden.value = false
        }
    }

    /**
     * Relative movement in, absolute position out.
     *
     * There were two cursors: the one this end draws and the one the host injects, each keeping its
     * own position from the same deltas. Two counters agree until one of them misses something —
     * a dropped line, a restarted app, a clamp at an edge one of them reached first — and after
     * that the arrow is drawn in one place and the click lands in another. Nothing tells you; you
     * simply start missing what you aim at.
     *
     * So there is one position now, kept here because this is the end that draws it, and the host
     * is told where the pointer *is* rather than how far it moved.
     */
    fun move(dx: Float, dy: Float) {
        val (width, height) = _state.value.size ?: return
        val moved = _cursor.updateAndGet { (x, y) ->
            (x + dx).coerceIn(0f, width - 1f) to (y + dy).coerceIn(0f, height - 1f)
        }
        send("p ${moved.first.round()} ${moved.second.round()}")
    }

    /** A mouse button, held for exactly as long as the finger is. 1 left, 2 right, 3 middle. */
    fun button(button: Int, down: Boolean) = send("b $button ${if (down) 1 else 0}")

    fun scroll(horizontal: Float, vertical: Float) =
        send("w ${horizontal.round()} ${vertical.round()}")

    fun key(keyCode: Int, metaState: Int = 0) = send("k $keyCode $metaState")

    fun type(text: String) = send("t $text")

    private fun Float.round() = String.format(java.util.Locale.US, "%.2f", this)

    /** Open an app on the desktop, as a window. */
    fun launch(component: String) = send("launch $component")

    /** Open one of the phone's own screens on the desktop, named by what it does. */
    fun open(action: String, data: String? = null) =
        send("open $action${if (data.isNullOrBlank()) "" else " $data"}")

    /** A second window of an app, on the desktop being looked at. */
    fun spawn(component: String) = send("spawn $component")

    /** Close an app, and mean it: the host force-stops it. */
    fun close(packageName: String) = send("close $packageName")

    /**
     * Where windows are allowed to open.
     *
     * Told to the host rather than worked out there, because the taskbar's height is a fact about
     * something the app draws and the host has never seen.
     */
    fun setArea(left: Int, top: Int, right: Int, bottom: Int) =
        send("area $left $top $right $bottom")

    /** Make the next click a right click, wherever it comes from. */
    fun armRightClick() = send("arm")

    /** Virtual desktops: show one, make one, take one away. */
    fun showDesktop(index: Int) = send("desk show $index")

    fun addDesktop() = send("desk add")

    fun removeDesktop(index: Int) = send("desk remove $index")

    /** Put an application's window in one of the regions: filled, a half, or a quarter. */
    fun arrange(packageName: String, where: String) = send("arrange $packageName $where")

    /** Bring a window back inside that area, for the one that is already off the screen. */
    fun fit(packageName: String) = send("fit $packageName")

    /** The two system switches the quick card offers. Flipped by the host, which is allowed to. */
    fun wifi(on: Boolean) = send("wifi ${if (on) 1 else 0}")

    fun batterySaver(on: Boolean) = send("saver ${if (on) 1 else 0}")

    /** Put the backdrop back in front, after something has covered it. */
    fun showBackdrop() = send("backdrop")

    /**
     * Close the stream, which is how the host is told to go.
     *
     * The host watches its own stdin; closing this is the end-of-input it waits for. Killing it any
     * other way leaves a virtual display and one of the device's few hardware encoders held by a
     * process nobody can see, and a handful of those stop any new session from starting at all.
     */
    fun stop() {
        scope.launch { halt() }
    }

    /**
     * Stop the desktop, and mean the whole of it.
     *
     * The host is not part of this app: it runs as the shell user, started over adb, and it holds
     * the display, the encoder and the socket the tablet is watching. Closing our end of the shell
     * asks it to leave, and a fresh app process has no end to close — so quitting the app and
     * reopening it to press Stop was a button with nothing behind it while the picture carried on.
     *
     * So the last word is a kill, sent over a shell of its own. It also clears out any host left
     * over from an earlier run of this app, which is why [start] waits for it before opening a new
     * one rather than racing it.
     */
    private suspend fun halt() {
        runCatching { stream?.close() }
        stream = null
        _state.update {
            it.copy(
                stage = DesktopStage.IDLE,
                message = "Desktop stopped",
                displayId = null,
                client = null,
                openApps = emptyList()
            )
        }
        sweep()
    }

    /**
     * Two words to a host on this phone, over loopback.
     *
     * The near door: no adb, no pairing, and it still answers when wireless debugging has switched
     * itself off — which it does every restart, and which used to leave a running host with no way
     * to be stopped at all.
     */
    private suspend fun knock(word: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            java.net.Socket().use { socket ->
                socket.connect(
                    java.net.InetSocketAddress("127.0.0.1", com.minimont.server.Server.DOOR_PORT),
                    500
                )
                socket.soTimeout = 1000
                socket.getOutputStream().apply {
                    write("$word\n".toByteArray())
                    flush()
                }
                socket.getInputStream().bufferedReader().readLine()
            }
        }.getOrNull()
    }

    /** Whether a host is up right now, whoever started it and whenever that was. */
    suspend fun hostStanding(): Boolean = knock("alive") == "yes"

    private suspend fun sweep() {
        // The near door first. It needs nothing to be switched on, so it is the one that works in
        // the case that matters: a host still holding the display after a restart.
        if (knock("quit") != null) {
            note("host asked to leave over the local door")
            return
        }
        if (!client.connected) {
            val host = mdns.host.value
            listOfNotNull(_state.value.connectPort, LEGACY_PORT).distinct()
                .any { client.connectTo(host, it).isSuccess }
        }
        if (!client.connected) return
        // The brackets are why this does not kill the shell running it: pkill matches its own
        // command line too, and a regex that reads as itself would be the last thing it matched.
        runCatching { client.shell("pkill -f 'com[.]minimont[.]server[.]Server'") }
            .onFailure { Log.w(TAG, "could not clear a leftover host", it) }
    }

    fun release() {
        stop()
        mdns.stop()
        client.release()
    }

    /** Change the size the desktop runs at, which means building it again. */
    fun setResolution(width: Int, height: Int) {
        start(width, height)
    }

    /** Reads `1808x1088` out of whatever follows it on the line. */
    private fun parseSize(text: String): Pair<Int, Int>? {
        val match = Regex("(\\d{3,5})x(\\d{3,5})").find(text) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return width to height
    }

    private fun note(line: String) {
        Log.i(TAG, line)
        _state.update { it.copy(log = (it.log + line).takeLast(LOG_LINES)) }
    }

    private fun fail(message: String, cause: Throwable? = null) {
        if (cause != null) Log.e(TAG, message, cause)
        _state.update { it.copy(stage = DesktopStage.FAILED, message = message) }
    }

    companion object {
        /**
         * One controller for the process.
         *
         * The cover screen starts the desktop and the service keeps it alive; both need the same
         * ADB stream, and two controllers would mean two shell processes, two virtual displays and
         * two of the device's few hardware encoders.
         */
        @Volatile
        private var shared: DesktopController? = null

        fun of(context: Context): DesktopController =
            shared ?: synchronized(this) {
                shared ?: DesktopController(context.applicationContext).also { shared = it }
            }

        /** The backdrop miniMont puts on its own display, as `am start` wants it. */
        const val BACKDROP = "com.minimont/.desktop.DesktopActivity"

        private const val TAG = "miniMont"
        private const val PREFERENCES = "minimont"
        private const val KEY_PAIRED = "paired"
        private const val LOG_LINES = 60

        /** adb over TCP, when somebody has turned it on. Same key, no code. */
        private const val LEGACY_PORT = 5555
    }
}
