package app.kinora.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * A visible outline on whatever inside has keyboard / remote-control (D-pad) focus. On a phone nothing is ever focused, so
 * this does nothing; on a TV it is the only way to see what the remote's OK button would press. Put it *before* `clickable`.
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    this.onFocusChanged { focused = it.hasFocus || it.isFocused }
        .then(if (focused) Modifier.border(3.dp, color, shape) else Modifier)
}

/** Material's IconButton plus [focusRing]. Declared here so every file in this package picks it up without changes. */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) = androidx.compose.material3.IconButton(onClick, modifier.focusRing(CircleShape), enabled, colors, interactionSource, content)

/** The shape of a Material button (a full pill), for rings drawn around buttons. */
val PillShape = RoundedCornerShape(50)

/**
 * A filter chip for remote-control screens. When it has focus it fills with the accent colour and grows a little, which is
 * clearer than an outline (and the outline used to sit on top of the chip's own border). "Selected" stays the soft container colour.
 */
@Composable
fun FocusChip(
    selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, label = "chip")
    androidx.compose.material3.FilterChip(
        selected = selected, onClick = onClick, enabled = enabled, label = label, leadingIcon = leadingIcon,
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            containerColor = if (focused) scheme.primary else androidx.compose.ui.graphics.Color.Transparent,
            labelColor = if (focused) scheme.onPrimary else scheme.onSurface,
            iconColor = if (focused) scheme.onPrimary else scheme.onSurfaceVariant,
            selectedContainerColor = if (focused) scheme.primary else scheme.secondaryContainer,
            selectedLabelColor = if (focused) scheme.onPrimary else scheme.onSecondaryContainer,
            selectedLeadingIconColor = if (focused) scheme.onPrimary else scheme.onSecondaryContainer,
        ),
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.hasFocus },
    )
}

/** Remembers which card on a TV screen had focus last, so closing the player can put the focus back there. */
class FocusMemory { var last: androidx.compose.ui.focus.FocusRequester? = null }

val LocalFocusMemory = androidx.compose.runtime.staticCompositionLocalOf { FocusMemory() }
