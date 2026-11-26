package app.kinora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The phone's top search bar on a screen with its own list (history, playlists, a folder on a share): the same shape as
 * Home's "Search YouTube" bar, but it filters what is on the screen as you type. [trailing] = that screen's buttons.
 */
@Composable
fun FilterBar(query: String, onQuery: (String) -> Unit, hint: String, trailing: @Composable () -> Unit = {}) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth().padding(12.dp, 8.dp, 8.dp, 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(scheme.surfaceContainerHigh).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = scheme.onSurfaceVariant)
            BasicTextField(
                query, onQuery, Modifier.weight(1f).padding(horizontal = 12.dp), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) Text(hint, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        field()
                    }
                },
            )
            if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Filled.Clear, "Clear search") }
        }
        trailing()
    }
}
