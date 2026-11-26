package app.kinora.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kinora.data.VideoInfo
import app.kinora.data.formatViews

private fun count(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> n.toString()
}.replace(".0", "")

/**
 * Who made it, the numbers, the description (tap to open it up) and the tags (tap one to search for it).
 * [expandedAlways] keeps the description open (the TV panel).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoDetails(info: VideoInfo, channelFallback: String, expandedAlways: Boolean = false, modifier: Modifier = Modifier) {
    val openSearch = LocalOpenSearch.current
    var open by remember(info) { mutableStateOf(expandedAlways) }
    val channel = info.channel.ifBlank { channelFallback }
    val stats = listOfNotNull(
        formatViews(info.views).takeIf { it.isNotBlank() },
        info.uploaded.takeIf { it.isNotBlank() },
        info.likes?.let { "${count(it)} likes" },
    ).joinToString(" · ")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // channel: tap it to search for more from them
        if (channel.isNotBlank()) Row(
            Modifier.fillMaxWidth().focusRing(RoundedCornerShape(12.dp)).clickable { openSearch(channel) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(channel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (info.verified) Icon(Icons.Filled.Verified, "Verified", modifier = Modifier.padding(0.dp), tint = MaterialTheme.colorScheme.primary)
                }
                info.subscribers?.let { Text("${count(it)} subscribers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        // description: a card that opens up
        if (info.description.isNotBlank() || stats.isNotBlank()) Surface(
            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(14.dp)).let { if (expandedAlways) it else it.clickable { open = !open } },
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stats.isNotBlank()) Text(stats, style = MaterialTheme.typography.labelLarge)
                if (open && info.category.isNotBlank()) Text(info.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info.description.isNotBlank()) LinkedText(
                    info.description.trim(), maxLines = if (open) Int.MAX_VALUE else 3,
                    onPlain = { if (!expandedAlways) open = !open },
                )
                if (!expandedAlways && info.description.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (open) "Show less" else "Show more", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private val URL_REGEX = Regex("""https?://[^\s<>"]+""")
private val VIDEO_LINK = Regex("""(?:youtube\.com/watch\?(?:.*&)?v=|youtu\.be/|youtube\.com/shorts/|youtube\.com/live/)([\w-]{11})""")

/** Plain text in which web addresses are links: a YouTube video link plays in the app, anything else opens in the browser. */
@Composable
private fun LinkedText(text: String, maxLines: Int, onPlain: () -> Unit) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val play = LocalPlay.current
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            URL_REGEX.findAll(text).forEach { m ->
                val url = m.value.trimEnd('.', ',', ')', ';', ':', '!', '?', ']')
                val end = m.range.first + url.length
                addStyle(androidx.compose.ui.text.SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), m.range.first, end)
                addStringAnnotation("URL", url, m.range.first, end)
            }
        }
    }
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        onClick = { offset ->
            val url = annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.item
            when {
                url == null -> onPlain() // not on a link: the card still opens and closes
                else -> {
                    val id = VIDEO_LINK.find(url)?.groupValues?.get(1)
                    if (id != null) play(listOf(app.kinora.data.Video(id, id)), 0)
                    else runCatching { uriHandler.openUri(url) }
                }
            }
        },
    )
}
