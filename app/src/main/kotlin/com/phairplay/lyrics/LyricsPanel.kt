package com.phairplay.lyrics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val LINE_END_GRACE_MS = 250L
private const val GAP_THRESHOLD_MS  = 4_000L
private const val GAP_FADEOUT_MS    = 500L

/** Smoothly interpolates between server position ticks at ~60fps. */
@Composable
fun rememberSmoothProgressMs(reportedMs: Long): Long {
    val anchorRealMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anchorPosMs  = remember { mutableLongStateOf(reportedMs) }
    var smoothMs by remember { mutableLongStateOf(reportedMs) }

    LaunchedEffect(reportedMs) {
        anchorPosMs.longValue  = reportedMs
        anchorRealMs.longValue = System.currentTimeMillis()
        smoothMs = reportedMs
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(16)
            smoothMs = anchorPosMs.longValue + (System.currentTimeMillis() - anchorRealMs.longValue)
        }
    }

    return smoothMs
}

@Composable
fun LyricsPanel(
    lyrics: List<LyricLine>,
    progressMs: Long,
    durationMs: Long = 0L,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val smoothMs = rememberSmoothProgressMs(progressMs)

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var userScrolled by remember { mutableStateOf(false) }
    var firstLoad by remember { mutableStateOf(true) }

    val passedIndex = lyrics.indexOfLast { it.startMs <= smoothMs }
    val activeIndex = if (passedIndex >= 0 && smoothMs <= lyrics[passedIndex].endMs + LINE_END_GRACE_MS)
        passedIndex else -1

    val scrollTarget = (passedIndex - 3).coerceAtLeast(0)

    LaunchedEffect(passedIndex, lyrics.size) {
        if (lyrics.isEmpty()) return@LaunchedEffect
        if (userScrolled) return@LaunchedEffect
        if (firstLoad) {
            listState.scrollToItem(scrollTarget)
            firstLoad = false
        } else {
            scope.launch { listState.animateScrollToItem(scrollTarget) }
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && userScrolled) {
            delay(5_000)
            userScrolled = false
        }
    }

    val nestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) userScrolled = true
                return Offset.Zero
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val panelHeight = if (maxHeight.value.isFinite() && maxHeight.value > 0) maxHeight else 600.dp

        Column(modifier = Modifier.height(panelHeight)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .nestedScroll(nestedScroll)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 80.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(lyrics, key = { it.startMs }) { line ->
                    val index = lyrics.indexOf(line)
                    val isActive = index == activeIndex
                    val isPast   = index < passedIndex || (index == passedIndex && activeIndex == -1)

                    val prevEnd = if (index > 0) lyrics[index - 1].endMs else 0L
                    val gapMs   = line.startMs - prevEnd
                    val inGap   = smoothMs in prevEnd until line.startMs

                    if (gapMs >= GAP_THRESHOLD_MS && inGap && index == passedIndex + 1) {
                        val dotsAlpha = if (line.startMs - smoothMs < GAP_FADEOUT_MS)
                            ((line.startMs - smoothMs).toFloat() / GAP_FADEOUT_MS).coerceIn(0f, 1f)
                        else 1f
                        MusicalDots(
                            fraction = ((smoothMs - prevEnd).toFloat() / gapMs).coerceIn(0f, 1f),
                            outerAlpha = dotsAlpha
                        )
                    }

                    LyricLineRow(
                        line     = line,
                        isActive = isActive,
                        isPast   = isPast,
                        progressMs = when {
                            isActive -> smoothMs
                            isPast   -> line.endMs
                            else     -> line.startMs - 1L
                        },
                        onSeek = { onSeek(line.startMs) }
                    )
                }
            }

            // Progress bar
            if (durationMs > 0) {
                val progress = (smoothMs.toFloat() / durationMs).coerceIn(0f, 1f)
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x33FFFFFF))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(Color.White)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(formatMs(smoothMs), fontSize = 11.sp, color = Color(0xFFAAAAAA))
                        Text("-${formatMs(durationMs - smoothMs)}", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    }
                }
            }
        }
    }
}

@Composable
fun MusicalDots(fraction: Float, outerAlpha: Float) {
    val dotStarts = listOf(0f, 0.24f, 0.48f)
    Row(
        modifier = Modifier
            .padding(start = 4.dp)
            .graphicsLayer { alpha = outerAlpha },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dotStarts.forEach { dotStart ->
            val lit = if (fraction >= 0.82f)
                1f - ((fraction - 0.82f) / 0.18f).coerceIn(0f, 1f)
            else
                ((fraction - dotStart) / 0.28f).coerceIn(0f, 1f)
            val color = lerp(Color(0xFF444444), Color.White, lit)
            val scale = 0.55f + 0.65f * lit
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .background(color, RoundedCornerShape(50))
            )
        }
    }
}

@Composable
fun LyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    progressMs: Long,
    onSeek: () -> Unit
) {
    val targetOpacity = when { isActive -> 1f; isPast -> 0.18f; else -> 0.25f }
    val targetScale   = if (isActive) 1.08f else 0.93f

    val opacity by animateFloatAsState(targetOpacity, tween(200), label = "opacity")
    val scale   by animateFloatAsState(targetScale,   tween(200), label = "scale")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = opacity; scaleX = scale; scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                clip = false
            }
            .clickable(onClick = onSeek)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column {
            val mainStyle = when {
                isActive -> TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    lineHeight = 34.sp, color = Color.White)
                isPast   -> TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal,
                    lineHeight = 27.sp, color = Color(0xFFCCCCCC))
                else     -> TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal,
                    lineHeight = 27.sp, color = Color(0xFF8E8E93))
            }

            if (isActive && line.words.isNotEmpty()) {
                Text(text = buildWordAnnotated(line.words, progressMs), style = mainStyle)
            } else {
                Text(text = line.text, style = mainStyle)
            }

            line.background?.let { bg ->
                val bgProgress = progressMs + 300L
                val bgLive = bgProgress in bg.startMs..(bg.endMs + 600L)
                val bgScale by animateFloatAsState(if (bgLive) 1.08f else 0.93f, tween(250), label = "bgScale")

                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier.graphicsLayer {
                    scaleX = bgScale; scaleY = bgScale; transformOrigin = TransformOrigin(0f, 0.5f)
                }) {
                    if (bgLive && bg.words.isNotEmpty()) {
                        Text(buildWordAnnotated(bg.words, bgProgress, dim = true),
                            style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold))
                    } else {
                        Text(bg.text, style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                            color = if (bgLive) Color(0xFFE0E0E0) else Color(0xFF6E6E73)))
                    }
                }
            }
        }
    }
}

private fun buildWordAnnotated(words: List<LyricWord>, progressMs: Long, dim: Boolean = false): AnnotatedString {
    val activeIdx = words.indexOfLast { it.startMs <= progressMs }
    return buildAnnotatedString {
        words.forEachIndexed { i, word ->
            val isCurrent  = i == activeIdx
            val isPastWord = i < activeIdx
            val dur = (word.endMs - word.startMs).coerceAtLeast(1L)
            val frac = if (isCurrent) ((progressMs - word.startMs).toFloat() / dur).coerceIn(0f, 1f)
                       else if (isPastWord) 1f else 0f

            val from  = if (dim) Color(0xFF6E6E73) else Color(0xFF8E8E93)
            val to    = if (dim) Color(0xFFE0E0E0) else Color.White
            val color = lerp(from, to, frac)

            val shadow = if (isCurrent && dur > 700L) {
                val glow = 1f - kotlin.math.abs(frac * 2f - 1f)
                Shadow(Color.White.copy(alpha = (if (dim) 0.4f else 0.55f) * glow), blurRadius = 18f)
            } else null

            withStyle(SpanStyle(color = color, shadow = shadow)) { append(word.text) }
            if (i < words.lastIndex) append(" ")
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
