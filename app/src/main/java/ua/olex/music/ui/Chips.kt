package ua.olex.music.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The app's filter chip, as Google's own apps draw them under Material 3 Expressive: tonal, with no
 * outline, a pill while off that squares its corners a little when chosen (the shape says "on" as
 * much as the colour does), springing between the two with the motion scheme.
 */
@Composable
internal fun AppChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 12.dp else 20.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "chip corner")
    androidx.compose.material3.FilterChip(
        selected = selected, onClick = onClick, label = label, modifier = modifier, enabled = enabled,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        shape = RoundedCornerShape(corner),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = cs.surfaceContainerHigh,
            labelColor = cs.onSurfaceVariant,
            iconColor = cs.onSurfaceVariant,
            selectedContainerColor = cs.secondaryContainer,
            selectedLabelColor = cs.onSecondaryContainer,
            selectedLeadingIconColor = cs.onSecondaryContainer,
            selectedTrailingIconColor = cs.onSecondaryContainer,
        ),
        border = null,
    )
}

/**
 * A few mutually exclusive choices as one connected button group (M3 Expressive): the chosen one
 * fills and rounds fully, its neighbours keep the tight inner corners, each half or third the width.
 */
@Composable
internal fun ConnectedChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    androidx.compose.foundation.layout.Row(modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, text ->
            androidx.compose.material3.ToggleButton(checked = i == selected, onCheckedChange = { if (i != selected) { haptics.tick(); onSelect(i) } },
                shapes = when (i) {
                    0 -> androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> androidx.compose.material3.ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = androidx.compose.ui.semantics.Role.RadioButton }) {
                androidx.compose.material3.Text(text, maxLines = 1)
            }
        }
    }
}
