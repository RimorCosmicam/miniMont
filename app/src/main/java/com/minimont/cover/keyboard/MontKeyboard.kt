package com.minimont.cover.keyboard

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.minimont.R
import com.minimont.cover.model.ModifierLockState
import com.minimont.cover.model.ModifierState
import com.minimont.cover.model.ModifierType
import com.minimont.cover.theme.MONT_SURFACE_ALPHA
import com.minimont.cover.theme.Mont
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** How long the release ring lives. Long enough to register, short enough not to trail typing. */
private const val FLASH_NANOS = 190_000_000f

// miniMate's figures: three rows of 37dp, 5dp apart, with the space row the same height below.
private const val ROW_HEIGHT_DP = 37f
private const val ROW_GAP_DP = 5f
private const val GRID_HEIGHT_DP = ROW_HEIGHT_DP * 3 + ROW_GAP_DP * 2

private data class MontKey(
    val label: String,
    val character: Char?,
    val keyCode: Int,
    val rect: Rect
)

/**
 * Three rows of letters, laid out in pixels because the grid is drawn rather than composed.
 *
 * Row two is inset by half a key so the stagger reads; row three carries shift and backspace on
 * 1.3 weights at either end. These are miniMate's own figures — 37dp rows, 4dp gaps, 5dp between
 * rows — because the layout is what the swipe decoder measures against.
 */
private fun montKeyLayout(width: Float, density: Float, scale: Float): List<MontKey> {
    if (width <= 0f) return emptyList()
    val gap = 4f * density
    // The grid's row height has to follow the same scale the box does. Fixing it at 37dp while the
    // box shrank meant the third row ran past the bottom edge and the space row was drawn over it.
    val rowHeight = ROW_HEIGHT_DP * density * scale
    val rowGap = ROW_GAP_DP * density * scale
    val keys = mutableListOf<MontKey>()

    fun addRow(text: String, row: Int, inset: Float = 0f) {
        val available = width - inset * 2f - gap * (text.length - 1)
        val keyWidth = available / text.length
        val top = row * (rowHeight + rowGap)
        text.forEachIndexed { index, char ->
            val left = inset + index * (keyWidth + gap)
            val lower = char.lowercaseChar()
            keys += MontKey(
                label = char.toString(),
                character = lower,
                keyCode = KeyEvent.KEYCODE_A + (lower - 'a'),
                rect = Rect(left, top, left + keyWidth, top + rowHeight)
            )
        }
    }

    addRow("QWERTYUIOP", 0)
    addRow("ASDFGHJKL", 1, 10f * density)

    val weights = listOf(1.3f) + List(7) { 1f } + listOf(1.3f)
    val unit = (width - gap * (weights.size - 1)) / weights.sum()
    var left = 0f
    val top = 2 * (rowHeight + rowGap)
    listOf("⇧", "Z", "X", "C", "V", "B", "N", "M", "⌫").forEachIndexed { index, label ->
        val keyWidth = unit * weights[index]
        val char = label.singleOrNull()?.takeIf { it in 'A'..'Z' }?.lowercaseChar()
        val keyCode = when (label) {
            "⇧" -> -1
            "⌫" -> KeyEvent.KEYCODE_DEL
            else -> KeyEvent.KEYCODE_A + (char!! - 'a')
        }
        keys += MontKey(label, char, keyCode, Rect(left, top, left + keyWidth, top + rowHeight))
        left += keyWidth + gap
    }
    return keys
}

private val glideWords = """
    a able about after again all also am an and any are as at away back be because been before being best better between big both but by
    call came can car come could day did do does done down each end even every feel find first for found from get give go going good got great
    had has have he help her here him his home how i if in into is it its just keep know last like little long look made make many may me more most
    much must my need never new next no not now of off old on one only or other our out over own people place please put really right said same say
    see she should so some something still such take tell than thank thanks that the their them then there these they thing think this those through
    time to too trackpad try two up us use very want was way we well went were what when where which while who why will with word work world would
    yes you your keyboard delete space hello hi love nice okay ok phone open close copy paste undo type typing swipe glide quick dex samsung
    today tomorrow yesterday morning night soon later around always another anything everyone everything nothing someone start stop move turn
    left right top bottom inside outside small large fast slow easy hard happy sorry sure maybe probably actually already almost enough ever
""".trimIndent().split(Regex("\\s+")).distinct()

/**
 * Matches a gesture against the dictionary by resampling both paths to a fixed number of points
 * and comparing them. A word's path is the straight run through its letters' key centres, so the
 * comparison is against where the finger would actually have gone — which is why this reads a
 * curve correctly where matching the sequence of keys crossed does not.
 */
private fun decodeSwipeWord(path: List<Offset>, keys: List<MontKey>, width: Float, height: Float): String {
    if (path.size < 2 || width <= 0f || height <= 0f) return ""
    val centres = keys.mapNotNull { key -> key.character?.let { it to key.rect.center } }.toMap()
    val letters = keys.filter { it.character != null }
    val first = letters.minByOrNull { hypot(path.first().x - it.rect.center.x, path.first().y - it.rect.center.y) }?.character
    val last = letters.minByOrNull { hypot(path.last().x - it.rect.center.x, path.last().y - it.rect.center.y) }?.character
    val samples = 24

    fun sample(points: List<Offset>, index: Int): Offset {
        if (points.size == 1) return points.first()
        val position = index.toFloat() / (samples - 1) * (points.size - 1)
        val low = position.toInt().coerceIn(0, points.lastIndex)
        val high = (low + 1).coerceAtMost(points.lastIndex)
        val fraction = position - low
        return Offset(
            points[low].x + (points[high].x - points[low].x) * fraction,
            points[low].y + (points[high].y - points[low].y) * fraction
        )
    }

    return glideWords.asSequence()
        .filter { it.length >= 2 && it.first() == first && it.last() == last }
        .minByOrNull { word ->
            val wordPath = word.mapNotNull { centres[it] }
            if (wordPath.isEmpty()) return@minByOrNull Float.MAX_VALUE
            var score = 0f
            for (index in 0 until samples) {
                val gesture = sample(path, index)
                val expected = sample(wordPath, index)
                score += hypot((gesture.x - expected.x) / width, (gesture.y - expected.y) / height)
            }
            score / samples + abs(path.size / 3f - word.length) * 0.012f
        } ?: ""
}

/**
 * miniMate's Mont keyboard.
 *
 * The letter grid is drawn on one Canvas rather than composed from three rows of buttons: at this
 * size the grid is the thing the swipe decoder measures against, so it has to exist as geometry
 * before it exists as widgets. Keys are 92% black, square and borderless; the held key is simply
 * lightened by 30% white, and a ring grows out of it on release so a quick tap is still visible
 * after the finger has gone.
 */
@Composable
fun MontKeyboard(
    modifierState: ModifierState,
    keyHeight: Float = 1f,
    onCharPress: (Char, Int) -> Unit,
    onKeyPress: (Int) -> Unit,
    onSwipeWord: (String) -> Unit,
    onModifierToggle: (ModifierType) -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val repeatScope = rememberCoroutineScope()
    val shifted = modifierState.shift.isActive

    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var trail by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var preview by remember { mutableStateOf("") }
    val keys = remember(surfaceSize, density, keyHeight) {
        montKeyLayout(surfaceSize.width.toFloat(), density, keyHeight)
    }

    val typeface = remember {
        runCatching { ResourcesCompat.getFont(context, R.font.poppins_black) }.getOrNull()
            ?: android.graphics.Typeface.DEFAULT_BOLD
    }

    // A key with no travel and no highlight gives nothing back — you cannot tell a press that
    // registered from one that missed.
    var heldKey by remember { mutableStateOf<Rect?>(null) }
    var flash by remember { mutableStateOf<Pair<Rect, Long>?>(null) }
    var frameNanos by remember { mutableStateOf(0L) }
    val animating = flash != null
    LaunchedEffect(animating) {
        while (animating) {
            withFrameNanos { frameNanos = it }
            flash?.let { if (frameNanos - it.second > FLASH_NANOS) flash = null }
        }
    }

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy((ROW_GAP_DP * keyHeight).dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height((GRID_HEIGHT_DP * keyHeight).dp)
                .onSizeChanged { surfaceSize = it }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val keyColor = Color.Black.copy(alpha = MONT_SURFACE_ALPHA)
                val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 12.sp.toPx()
                    this.typeface = typeface
                }
                keys.forEach { key ->
                    drawRect(keyColor, key.rect.topLeft, key.rect.size)
                    if (heldKey == key.rect) {
                        drawRect(Color.White.copy(alpha = 0.30f), key.rect.topLeft, key.rect.size)
                    }
                    val label = if (shifted && key.character != null) key.label.uppercase() else {
                        key.character?.toString() ?: key.label
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        key.rect.center.x,
                        key.rect.center.y - (textPaint.ascent() + textPaint.descent()) / 2f,
                        textPaint
                    )
                }

                flash?.let { (rect, startedAt) ->
                    val age = ((frameNanos - startedAt).toFloat() / FLASH_NANOS).coerceIn(0f, 1f)
                    val grow = age * 5f * density
                    drawRect(
                        Color.White.copy(alpha = (1f - age) * 0.55f),
                        Offset(rect.left - grow, rect.top - grow),
                        Size(rect.width + grow * 2f, rect.height + grow * 2f),
                        style = Stroke(1.5.dp.toPx())
                    )
                    drawRect(Color.White.copy(alpha = (1f - age) * 0.18f), rect.topLeft, rect.size)
                }

                if (trail.size > 1) {
                    for (index in 1 until trail.size) {
                        val phase = index.toFloat() / trail.lastIndex.coerceAtLeast(1)
                        drawLine(
                            Color.White.copy(alpha = 0.12f + phase * 0.78f),
                            trail[index - 1],
                            trail[index],
                            (0.8f + phase * 5.2f).dp.toPx()
                        )
                        if (index == trail.lastIndex) {
                            drawCircle(Color.White.copy(alpha = 0.92f), 4.5.dp.toPx(), trail[index])
                        }
                    }
                }
            }

            Box(
                Modifier.fillMaxSize().pointerInput(surfaceSize, shifted) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downKey = keys.firstOrNull { it.rect.contains(down.position) }
                        var moved = false
                        var repeated = false
                        var repeatJob: Job? = null
                        trail = listOf(down.position)
                        preview = ""
                        heldKey = downKey?.rect

                        if (downKey?.label == "⌫") {
                            onHaptic()
                            onKeyPress(KeyEvent.KEYCODE_DEL)
                            repeatJob = repeatScope.launch {
                                delay(340)
                                repeated = true
                                while (true) {
                                    onKeyPress(KeyEvent.KEYCODE_DEL)
                                    delay(52)
                                }
                            }
                        } else if (downKey?.character != null) {
                            repeatJob = repeatScope.launch {
                                delay(380)
                                if (!moved) {
                                    repeated = true
                                    onHaptic()
                                    while (true) {
                                        val char = if (shifted) {
                                            downKey.character.uppercaseChar()
                                        } else {
                                            downKey.character
                                        }
                                        onCharPress(char, downKey.keyCode)
                                        delay(72)
                                    }
                                }
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val position = change.position
                            val distance = hypot(
                                position.x - down.position.x,
                                position.y - down.position.y
                            )
                            if (distance > 10f * density && !repeated) {
                                moved = true
                                repeatJob?.cancel()
                            }
                            val step = trail.lastOrNull()?.let {
                                hypot(position.x - it.x, position.y - it.y)
                            } ?: Float.MAX_VALUE
                            if (step > 3f * density) {
                                trail = trail + position
                                if (moved && trail.size >= 4) {
                                    preview = decodeSwipeWord(
                                        trail,
                                        keys,
                                        size.width.toFloat(),
                                        size.height.toFloat()
                                    )
                                }
                            }
                            change.consume()
                        }
                        repeatJob?.cancel()
                        heldKey = null
                        if (!moved && downKey != null) flash = downKey.rect to System.nanoTime()

                        if (moved) {
                            val word = decodeSwipeWord(
                                trail,
                                keys,
                                size.width.toFloat(),
                                size.height.toFloat()
                            )
                            if (word.isNotEmpty()) {
                                onHaptic()
                                onSwipeWord(
                                    if (shifted) word.replaceFirstChar { it.uppercase() } else word
                                )
                            }
                        } else if (!repeated && downKey != null && downKey.label != "⌫") {
                            when (downKey.label) {
                                "⇧" -> onModifierToggle(ModifierType.SHIFT)
                                else -> downKey.character?.let { char ->
                                    onCharPress(
                                        if (shifted) char.uppercaseChar() else char,
                                        downKey.keyCode
                                    )
                                }
                            }
                        }
                        trail = emptyList()
                        preview = ""
                    }
                }
            )

            if (preview.isNotEmpty()) {
                androidx.compose.material3.Text(
                    text = preview.uppercase(),
                    color = Color.Black,
                    fontFamily = Mont,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(Color.White)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MontRowKey("CTRL", 0.9f, keyHeight, modifierState.ctrl.isActive) { onModifierToggle(ModifierType.CTRL) }
            MontRowKey("ALT", 0.82f, keyHeight, modifierState.alt.isActive) { onModifierToggle(ModifierType.ALT) }
            MontRowKey("⌘", 0.82f, keyHeight, modifierState.meta.isActive) { onModifierToggle(ModifierType.META) }
            MontRowKey("SPACE", 4.2f, keyHeight, repeatable = true) { onKeyPress(KeyEvent.KEYCODE_SPACE) }
            MontRowKey("↵", 1.15f, keyHeight, repeatable = true) { onKeyPress(KeyEvent.KEYCODE_ENTER) }
        }
    }
}

/**
 * A key on the bottom row. Square, 92% black, borderless; active lifts to a lighter grey with the
 * one border the language allows, so a latched modifier reads as held down rather than as chosen.
 */
@Composable
private fun RowScope.MontRowKey(
    label: String,
    weight: Float,
    scale: Float,
    selected: Boolean = false,
    repeatable: Boolean = false,
    onClick: () -> Unit
) {
    val repeatScope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    val active = held || selected

    val background = if (active) {
        Color(0xFF2E2E33).copy(alpha = MONT_SURFACE_ALPHA)
    } else {
        Color.Black.copy(alpha = MONT_SURFACE_ALPHA)
    }

    val action = if (repeatable) {
        Modifier.pointerInput(onClick) {
            detectTapGestures(onPress = {
                held = true
                onClick()
                val repeatJob = repeatScope.launch {
                    delay(360)
                    while (true) {
                        onClick()
                        delay(55)
                    }
                }
                tryAwaitRelease()
                repeatJob.cancel()
                held = false
            })
        }
    } else {
        Modifier.pointerInput(onClick) {
            detectTapGestures(
                onPress = {
                    held = true
                    tryAwaitRelease()
                    held = false
                },
                onTap = { onClick() }
            )
        }
    }

    Box(
        Modifier
            .weight(weight)
            .height((ROW_HEIGHT_DP * scale).dp)
            .background(background)
            .border(1.dp, if (active) Color.White.copy(alpha = 0.55f) else Color.Transparent)
            .then(action),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            text = label,
            color = Color.White,
            fontFamily = Mont,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp
        )
    }
}
