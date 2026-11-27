@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The owner's app family (Earbuds, VPN, Music) shares one settings language, taken from Android 17's
 * own Settings: text rows in connected groups, coloured discs only on entries that open a section,
 * the Pixel switch, and choices on a page of their own. These are Music's copies of the same parts.
 */

/**
 * The top level of Android Settings marks each entry with a bright disc of a fixed hue and a filled
 * symbol in a deep tone of the same hue (sound pink, connections cyan, storage yellow...). Only the
 * entries that lead somewhere get one; rows inside a page are text only.
 */
internal enum class Accent(val disc: Long, val ink: Long) {
    SOUND(0xFFFFAFD8, 0xFF5F1145), CONNECTION(0xFF7DD3F7, 0xFF003548), APPS(0xFFA8C7FA, 0xFF0B305F),
    BATTERY(0xFF86D993, 0xFF003916), DISPLAY(0xFFFFB68A, 0xFF552100), STORAGE(0xFFFDC424, 0xFF3F2E00), SYSTEM(0xFFC4C7C5, 0xFF2D3133),
}

@Composable
internal fun AccentDisc(glyph: Glyph, accent: Accent) {
    Surface(shape = CircleShape, color = Color(accent.disc), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Symbol(glyph, size = 24.dp, color = Color(accent.ink), filled = true) }
    }
}

/**
 * Pixel Settings' switch (SettingsLib's expressive MaterialSwitch): the thumb always carries an
 * icon, a check when on and a cross when off; the thumb slides without overshoot and grows while
 * pressed. A null [onCheckedChange] leaves the tap to the row around it.
 */
@Composable
internal fun PixelSwitch(
    checked: Boolean, modifier: Modifier = Modifier, onCheckedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true, interactionSource: MutableInteractionSource? = null,
) {
    val cs = MaterialTheme.colorScheme
    MaterialTheme(motionScheme = MotionScheme.standard()) {
        Switch(
            checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled, interactionSource = interactionSource,
            colors = SwitchDefaults.colors(uncheckedThumbColor = cs.outline, uncheckedIconColor = cs.surfaceContainerHighest),
            thumbContent = {
                Symbol(if (checked) Glyph.CHECK else Glyph.CLOSE, size = SwitchDefaults.IconSize,
                    color = if (checked) cs.primary else cs.surfaceContainerHighest, pressed = false)
            },
        )
    }
}

/** A section title over a group: primary colour, titleSmall, 16 dp in from the group's edge. */
@Composable
internal fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() }.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
}

/** Where a row sits in its group: the group's outer corners are round, the inner ones tight. */
internal data class RowPlace(val first: Boolean, val last: Boolean) {
    companion object { val Alone = RowPlace(first = true, last = true) }
}

internal const val ROW_GAP_DP = 2

/**
 * One row of a settings group, as Android 17 draws it: the group's outer corners round (28 dp), the
 * joins tight (4 dp) with a 2 dp gap; a press rounds the row's own corners a little toward a pill,
 * on the same spring as the rest of the app, so the finger sees which row it holds. Text only:
 * [leading] is for entries that open a section ([AccentDisc]).
 */
@Composable
internal fun FamilyRow(
    place: RowPlace, title: String, summary: String?, modifier: Modifier = Modifier,
    enabled: Boolean = true, danger: Boolean = false, leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null, toggle: Boolean? = null, selected: Boolean? = null,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val pressed by interaction.collectIsPressedAsState()
    val still = LocalPowerSave.current
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val outer = 28.dp
    val inner = 4.dp
    val lift = if (pressed && !still) 10.dp else 0.dp
    val top by animateDpAsState(if (place.first) outer else inner + lift, spec, label = "Row top corners")
    val bottom by animateDpAsState(if (place.last) outer else inner + lift, spec, label = "Row bottom corners")
    val shape = RoundedCornerShape(top, top, bottom, bottom)
    val semantics = Modifier.semantics {
        when {
            toggle != null -> { role = Role.Switch; toggleableState = ToggleableState(toggle) }
            selected != null -> { role = Role.RadioButton; this.selected = selected }
        }
    }
    val content: @Composable () -> Unit = {
        Row(Modifier.heightIn(min = 72.dp).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) cs.error else cs.onSurface)
                if (summary != null) AnimatedContent(summary, label = "Row summary",
                    transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) }) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
    }
    // Music's pages sit on the plain surface (Earbuds and VPN on surfaceContainer), so its rows
    // take one step more tone to keep the same contrast against the page.
    val container = cs.surfaceContainerHigh
    if (onClick != null) Surface(onClick = onClick, enabled = enabled, shape = shape, color = container, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().then(semantics), content = content)
    else Surface(shape = shape, color = container, modifier = modifier.fillMaxWidth().then(semantics), content = content)
}

/** A setting's on/off: the whole row toggles, the Pixel switch on its vertical centre shows the state. */
@Composable
internal fun SwitchRow(place: RowPlace, title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val press = remember { MutableInteractionSource() }
    FamilyRow(place, title, summary, enabled = enabled, toggle = checked, interaction = press,
        trailing = { PixelSwitch(checked, enabled = enabled, interactionSource = press) }) {
        haptics.toggle(!checked); onChange(!checked)
    }
}

/**
 * A choice's own page, as Android Settings opens one (Screen timeout, Dark theme): the options as
 * radio rows in one group; picking one goes straight back.
 */
@Composable
internal fun ChoicePage(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, footer: String? = null, onSelect: (Int) -> Unit) {
    val haptics = rememberHaptics()
    val body: @Composable () -> Unit = {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalIconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Back" }) { Symbol(Glyph.BACK) }
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                }
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
                    options.forEachIndexed { i, label ->
                        FamilyRow(RowPlace(i == 0, i == options.lastIndex), label, null, selected = i == selected,
                            leading = { RadioButton(selected = i == selected, onClick = null) }) {
                            if (i != selected) { haptics.tick(); onSelect(i) }
                            onDismiss()
                        }
                    }
                }
                if (footer != null) Text(footer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            }
        }
    }
    if (LocalInlineSheets.current) body()
    else androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { body() }
}

/** Builds a group of rows that know their place in it, for groups whose rows are decided at runtime. */
@Composable
internal fun FamilyGroup(title: String?, rows: List<@Composable (RowPlace) -> Unit>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        if (title != null) GroupTitle(title)
        Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
            rows.forEachIndexed { i, row -> row(RowPlace(i == 0, i == rows.lastIndex)) }
        }
    }
}

/**
 * A number whose digits roll when they change, each on its own: up when the digit grows, down when
 * it falls, on the fast spatial spring. Digits are tabular, so nothing beside them moves.
 */
@Composable
internal fun RollingNumber(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val still = LocalPowerSave.current
    val base = style.copy(fontFeatureSettings = "tnum")
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            androidx.compose.runtime.key(text.length - i) {
                AnimatedContent(ch, label = "Digit", transitionSpec = {
                    val up = targetState > initialState
                    if (still || !initialState.isDigit() || !targetState.isDigit()) fadeIn(effects) togetherWith fadeOut(effects)
                    else (androidx.compose.animation.slideInVertically(spatial) { h -> if (up) h else -h } + fadeIn(effects)) togetherWith
                        (androidx.compose.animation.slideOutVertically(spatial) { h -> if (up) -h else h } + fadeOut(effects)) using
                        androidx.compose.animation.SizeTransform(clip = true)
                }) { c -> Text(c.toString(), style = base, maxLines = 1, softWrap = false) }
            }
        }
    }
}
