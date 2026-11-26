package app.kinora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import app.kinora.data.qualityLabel
import app.kinora.tubeApp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sign

/*
 * What the phone and TV players share, so they look and behave the same (the web player follows the same design):
 * YouTube's red seek bar, white icons, the "10 seconds" ripple, the play/pause flash and the settings list.
 */

val PlayerRed = Color(0xFFFF0033)
val PlayerPanel = Color(0xF21F1F1F)
private val PlayerInk = Color(0xFF0F0F0F)
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val QUALITIES = listOf(360, 480, 720, 1080, 1440, 2160)

fun speedLabel(s: Float) = if (s == 1f) "Normal" else s.toString().removeSuffix(".0") + "×"

/** The progress bar's picture: what is played (red), what is loaded (light) and the rest. [knob] 0 = no dot. */
@Composable
fun SeekTrack(positionMs: Long, bufferedMs: Long, durationMs: Long, thickness: Dp, knob: Dp, modifier: Modifier = Modifier) {
    // with no known length the bar stays empty (never full)
    fun at(ms: Long) = if (durationMs > 0) (ms.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Canvas(modifier.fillMaxWidth().height(max(thickness, knob))) {
        val h = thickness.toPx()
        val top = (size.height - h) / 2
        val round = CornerRadius(h / 2)
        fun bar(fraction: Float, color: Color) {
            if (fraction > 0f) drawRoundRect(color, Offset(0f, top), Size(size.width * fraction, h), round)
        }
        bar(1f, Color(0x40FFFFFF))
        bar(at(bufferedMs), Color(0x66FFFFFF))
        bar(at(positionMs), PlayerRed)
        if (knob > 0.dp && durationMs > 0) drawCircle(PlayerRed, knob.toPx() / 2, Offset(size.width * at(positionMs), size.height / 2))
    }
}

/** Seconds skipped by the presses so far ("30 seconds" after three), shown for a moment on that side of the picture. */
class SkipState {
    var seconds by mutableIntStateOf(0); private set
    var tick by mutableIntStateOf(0); private set
    fun add(s: Int) { seconds = if (seconds != 0 && seconds.sign == s.sign) seconds + s else s; tick++ }
    fun clear() { seconds = 0 }
}

@Composable
fun rememberSkipState(): SkipState {
    val state = remember { SkipState() }
    LaunchedEffect(state.tick) { delay(700); state.clear() }
    return state
}

@Composable
fun BoxScope.SkipFeedback(state: SkipState, textSize: TextUnit = 14.sp) {
    val secs = state.seconds
    if (secs == 0) return
    val back = secs < 0
    val shape = if (back) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
    Box(
        Modifier.align(if (back) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.34f).clip(shape).background(Color(0x24FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (back) "‹‹‹" else "›››", color = Color.White, fontSize = textSize * 1.8f, fontWeight = FontWeight.Bold)
            Text("${abs(secs)} seconds", color = Color.White, fontSize = textSize, fontWeight = FontWeight.Medium)
        }
    }
}

/** The icon that pops in the middle and fades when you play or pause. */
class FlashState {
    var icon by mutableStateOf<ImageVector?>(null); private set
    var tick by mutableIntStateOf(0); private set
    fun show(i: ImageVector) { icon = i; tick++ }
}

@Composable
fun BoxScope.PlayerFlash(state: FlashState, size: Dp = 72.dp) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(state.tick) { if (state.tick > 0) { progress.snapTo(0f); progress.animateTo(1f, tween(500)) } }
    val icon = state.icon ?: return
    if (progress.value >= 1f) return
    Box(
        Modifier.align(Alignment.Center).size(size)
            .graphicsLayer { val s = 0.8f + progress.value * 0.7f; scaleX = s; scaleY = s; alpha = 1f - progress.value }
            .clip(CircleShape).background(Color(0x8C000000)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.55f)) }
}

// ----- the settings menu: a list of settings, each opening its choices -----

class PlayerOption(val label: String, val selected: Boolean, val enabled: Boolean = true, val pick: () -> Unit)

class PlayerSetting(
    val id: String, val icon: ImageVector, val label: String, val value: String = "",
    val options: List<PlayerOption> = emptyList(),
    val checked: Boolean? = null, // set for an on/off row
    val enabled: Boolean = true,
    val onClick: () -> Unit = {},
)

/** What can be set for what is playing now. The same rows, in the same order, as the web player's menu. */
@Composable
@UnstableApi
fun playerSettings(): List<PlayerSetting> {
    val app = LocalContext.current.tubeApp
    val pb = LocalPlayback.current
    val audio = app.stream == "audio"
    return buildList {
        if (!audio) add(
            PlayerSetting("quality", Icons.Filled.HighQuality, "Quality", qualityLabel(app.quality), QUALITIES.map { q ->
                PlayerOption(qualityLabel(q), app.quality == q) { app.ovQuality = if (q == app.store.quality) null else q; pb.reload(true) }
            }),
        )
        add(PlayerSetting("speed", Icons.Filled.Speed, "Playback speed", speedLabel(pb.speed), SPEEDS.map { s -> PlayerOption(speedLabel(s), pb.speed == s) { pb.changeSpeed(s) } }))
        val texts = pb.textTracks
        add(
            PlayerSetting(
                "captions", Icons.Filled.ClosedCaption, "Captions",
                if (texts.isEmpty()) "Unavailable" else texts.firstOrNull { it.selected }?.label ?: "Off",
                if (texts.isEmpty()) emptyList() else listOf(PlayerOption("Off", !pb.subtitlesOn) { pb.selectSubtitles(null) }) +
                    texts.map { t -> PlayerOption(t.label + if (t.supported) "" else " · not supported", t.selected, t.supported) { pb.selectSubtitles(t) } },
                enabled = texts.isNotEmpty(),
            ),
        )
        if (pb.audioTracks.size > 1) add(
            PlayerSetting("track", Icons.Filled.Audiotrack, "Audio track", pb.audioTracks.firstOrNull { it.selected }?.label.orEmpty(), pb.audioTracks.map { t ->
                PlayerOption(t.label + if (t.supported) "" else " · not supported", t.selected, t.supported) { pb.selectAudio(t) }
            }),
        )
        add(PlayerSetting("audio", Icons.Filled.Headphones, "Audio only", checked = audio) {
            val next = if (audio) "video" else "audio"
            app.ovStream = if (next == app.store.stream) null else next; pb.reload(true)
        })
        add(PlayerSetting("autoplay", Icons.Filled.PlaylistPlay, "Autoplay next", checked = app.autoplay) {
            val next = !app.autoplay
            app.ovAutoplay = if (next == app.store.autoplay) null else next; pb.reload(false)
        })
        if (app.hasOverrides) add(PlayerSetting("reset", Icons.Filled.Refresh, "Reset to my defaults") { app.resetOverrides(); pb.reload(true) })
    }
}

/** The menu itself. Works by touch and by remote: the focused row turns white, and Back leaves a list of choices. */
@Composable
fun PlayerSettingsList(rows: List<PlayerSetting>, modifier: Modifier = Modifier, textSize: TextUnit = 15.sp) {
    var page by remember { mutableStateOf<String?>(null) }
    var cameFrom by remember { mutableStateOf<String?>(null) }
    val sub = rows.firstOrNull { it.id == page && it.options.isNotEmpty() }
    val focus = remember { FocusRequester() }
    // where the remote lands: the chosen option on a list of choices, else the row just left, else the first one
    val rootTarget = rows.firstOrNull { it.id == cameFrom }?.id ?: rows.firstOrNull { it.enabled }?.id
    val subTarget = sub?.options?.let { o -> o.indexOfFirst { it.selected && it.enabled }.takeIf { it >= 0 } ?: o.indexOfFirst { it.enabled } }
    LaunchedEffect(sub?.id) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    fun back() { cameFrom = page; page = null }
    BackHandler(sub != null) { back() }

    val scroll = rememberScrollState()
    LaunchedEffect(sub?.id) { scroll.scrollTo(0) }
    Column(modifier.verticalScroll(scroll).padding(8.dp)) {
        if (sub != null) {
            SettingLine(Icons.AutoMirrored.Filled.ArrowBack, sub.label, textSize, bold = true, onClick = ::back)
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Color(0x26FFFFFF)))
            sub.options.forEachIndexed { i, o ->
                SettingLine(
                    if (o.selected) Icons.Filled.Check else null, o.label, textSize, enabled = o.enabled,
                    modifier = if (i == subTarget) Modifier.focusRequester(focus) else Modifier,
                ) { o.pick(); back() }
            }
        } else rows.forEach { r ->
            SettingLine(
                r.icon, r.label, textSize, value = r.value, checked = r.checked, opens = r.options.isNotEmpty(), enabled = r.enabled,
                modifier = if (r.id == rootTarget) Modifier.focusRequester(focus) else Modifier,
            ) { if (r.options.isNotEmpty()) page = r.id else r.onClick() }
        }
    }
}

@Composable
private fun SettingLine(
    icon: ImageVector?, text: String, textSize: TextUnit, modifier: Modifier = Modifier, value: String = "", checked: Boolean? = null,
    opens: Boolean = false, enabled: Boolean = true, bold: Boolean = false, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ink = if (focused) PlayerInk else Color.White
    Row(
        modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(24.dp)) else Box(Modifier.size(24.dp))
        Text(text, color = ink, fontSize = textSize, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = ink.copy(alpha = 0.65f), fontSize = textSize * 0.9f, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.7f, fill = false))
        if (checked != null) Switch(checked, null, colors = SwitchDefaults.colors(checkedTrackColor = PlayerRed, checkedThumbColor = Color.White))
        if (opens) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = ink.copy(alpha = 0.65f), modifier = Modifier.size(22.dp))
    }
}
