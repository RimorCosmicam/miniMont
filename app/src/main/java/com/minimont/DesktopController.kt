package com.minimont

import android.content.Context
import android.util.Log
import com.minimont.adb.AdbClient
import com.minimont.adb.AdbMdns
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
private val FALLBACK = listOf(1920 to 1080, 1600 to 900, 1280 to 800)

private fun snap(value: Double): Int =
    (Math.round(value / 16).toInt() * 16).coerceAtLeast(16)

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
    val openApps: List<String> = emptyList()
) {
    /**
     * The sizes worth offering, at the client's own shape.
     *
     * The same derivation the client and the Mac both make, so no two ends of AirMate ever propose
     * shapes the others do not recognise. Sides snap to multiples of sixteen because hardware
     * decoders refuse anything else however far inside their stated limits it is, and anything
     * above the client's ceiling is dropped — past that there is no picture at all.
     */
    val choices: List<Pair<Int, Int>>
        get() {
            val screen = panel ?: return FALLBACK
            val derived = listOf(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4)
                .map { scale -> snap(screen.first * scale) to snap(screen.second * scale) }
                .filter { it.first >= 640 && it.second >= 480 }
                .filter { candidate ->
                    val limit = ceiling ?: return@filter true
                    candidate.first <= limit.first && candidate.second <= limit.second
                }
                .distinct()
                .take(4)
            return derived.ifEmpty { FALLBACK }
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

    /** True once adbd has our key, so a later start needs no code. */
    val paired: Boolean
        get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAIRED, false)

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
                    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_PAIRED, true).apply()
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
    fun start(width: Int = 1920, height: Int = 1080, density: Int = 160) {
        scope.launch {
            stop()
            val port = _state.value.connectPort
            if (port == null) {
                fail("Wireless debugging is not switched on, so there is nothing to connect to.")
                return@launch
            }
            _state.update { it.copy(stage = DesktopStage.CONNECTING, message = "Connecting…") }
            val host = mdns.host.value
            client.connectTo(host, port).onFailure { failure ->
                // A key adbd has forgotten looks exactly like a key it never had.
                fail("The device would not accept the connection. Pair again.", failure)
                return@launch
            }
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
                            if (size != null) _state.update { it.copy(size = size) }
                        }

                        line.contains("client panel ") -> {
                            val panel = parseSize(line.substringAfter("client panel "))
                            val ceiling = parseSize(line.substringAfter("decoder ceiling "))
                            _state.update { it.copy(panel = panel, ceiling = ceiling) }
                        }

                        line.contains("BACKDROP FAILED") -> _state.update {
                            it.copy(
                                stage = DesktopStage.FAILED,
                                message = "The display would not accept a window. " +
                                    "Try it with One UI's own decorations switched on."
                            )
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
        val open = stream ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val out = open.openOutputStream()
                    out.write((line + "\n").toByteArray())
                    out.flush()
                }
            }.onFailure { Log.e(TAG, "could not send: $line", it) }
        }
    }

    /** Open an app on the desktop, as a window. */
    fun launch(component: String) = send("launch $component")

    /** Close an app, and mean it: the host force-stops it. */
    fun close(packageName: String) = send("close $packageName")

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
        runCatching { stream?.close() }
        stream = null
        _state.update {
            it.copy(
                stage = DesktopStage.IDLE,
                message = "Desktop stopped",
                displayId = null,
                openApps = emptyList()
            )
        }
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
    }
}
