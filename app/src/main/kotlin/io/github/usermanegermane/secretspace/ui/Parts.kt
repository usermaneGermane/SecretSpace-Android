package io.github.usermanegermane.secretspace.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

// ---------------------------------------------------------------------- a screen
/** A screen: its bar (title, back, actions) on the window colour, then what it holds. */
@Composable
fun Page(title: String, back: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {},
         content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Look.c.bg)) {
        Bar(title, back, actions)
        content()
    }
}

@Composable
fun Bar(title: String, back: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    if (back != null) BackHandler(onBack = back)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Sp.xs), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = back) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Look.c.text)
        } else Spacer(Modifier.width(Sp.m))
        Text(title, style = Type.heading, color = Look.c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = Sp.xs))
        actions()
        Spacer(Modifier.width(Sp.xs))
    }
}

/** A scrolling body with the page's side margins. */
@Composable
fun Body(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = Sp.l).padding(bottom = Sp.xxl), content = content)
}

// ---------------------------------------------------------------------- surfaces
/** A card on the window colour: no border (DESIGN-LOOK: a line only inside a card). */
@Composable
fun Card(modifier: Modifier = Modifier, color: Color = Look.c.card, padding: Dp = Sp.l,
         content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(Radius.card).background(color).padding(padding), content = content)
}

/** A card's or a section's small heading: "Notes", "Sync server". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.smallBold, color = Look.c.muted, modifier = modifier.padding(bottom = Sp.s))
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.small) {
    Text(text, style = style, color = Look.c.muted, modifier = modifier)
}

@Composable
fun Gap(height: Dp = Sp.m) = Spacer(Modifier.height(height))

// ---------------------------------------------------------------------- buttons
private val MAIN_HEIGHT = 48.dp
private val QUIET_HEIGHT = 40.dp

/** The one green button of a screen: its main action. */
@Composable
fun MainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = Radius.button,
        modifier = modifier.heightIn(min = MAIN_HEIGHT),
        colors = ButtonDefaults.buttonColors(containerColor = Look.c.green, contentColor = Look.c.onAccent,
            disabledContainerColor = Look.c.green.copy(alpha = 0.35f), disabledContentColor = Look.c.onAccent.copy(alpha = 0.7f)),
        contentPadding = PaddingValues(horizontal = Sp.xl)) {
        Text(text, style = Type.label, maxLines = 1, softWrap = false)
    }
}

/** Every other button: a thin line around plain text. ``danger`` writes it in the danger colour. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                danger: Boolean = false) {
    val ink = if (danger) Look.c.danger else Look.c.text
    OutlinedButton(onClick = onClick, enabled = enabled, shape = Radius.button,
        modifier = modifier.heightIn(min = QUIET_HEIGHT),
        border = BorderStroke(1.dp, Look.c.border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = ink,
            disabledContentColor = ink.copy(alpha = 0.4f)),
        contentPadding = PaddingValues(horizontal = Sp.l)) {
        Text(text, style = Type.label, maxLines = 1, softWrap = false)
    }
}

/** A red button, for what cannot be undone (in a dialog that asks first). */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = Radius.button,
        modifier = modifier.heightIn(min = MAIN_HEIGHT),
        colors = ButtonDefaults.buttonColors(containerColor = Look.c.danger, contentColor = Look.c.onDanger),
        contentPadding = PaddingValues(horizontal = Sp.xl)) {
        Text(text, style = Type.label, maxLines = 1, softWrap = false)
    }
}

/** A link in green text, for a way elsewhere ("Create an account"). */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, muted: Boolean = false) {
    TextButton(onClick = onClick, modifier = modifier, shape = Radius.button) {
        Text(text, style = Type.label, color = if (muted) Look.c.muted else Look.c.green)
    }
}

// ---------------------------------------------------------------------- tiles
/** A site's or a vault's letter on its pastel tile (widgets.py): the colour fixed by the name. */
@Composable
fun Tile(name: String, size: Dp = 42.dp) {
    val (bg, ink) = Look.c.tile(name)
    Box(Modifier.size(size).clip(RoundedCornerShape(if (size >= 60.dp) 16.dp else 12.dp)).background(bg),
        contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase().ifEmpty { "?" }, color = ink, style = Type.bodyBold,
            fontSize = (size.value * 0.4f).sp)
    }
}

/** A person: their initials on a round tile. */
@Composable
fun Avatar(name: String, size: Dp = 40.dp) {
    val (bg, ink) = Look.c.tile(name)
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(name.take(2).uppercase().ifEmpty { "?" }, color = ink, style = Type.bodyBold,
            fontSize = (size.value * 0.36f).sp)
    }
}

/** A list row (widgets.entry_row): a card of its own with the tile, the name in bold, one quiet line, a time. */
@Composable
fun RowCard(name: String, line: String, onClick: () -> Unit, modifier: Modifier = Modifier, side: String = "",
            trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 68.dp).clip(Radius.row).background(Look.c.card)
        .clickable(onClick = onClick).padding(horizontal = Sp.m, vertical = Sp.m),
        verticalAlignment = Alignment.CenterVertically) {
        Tile(name)
        Spacer(Modifier.width(Sp.m))
        Column(Modifier.weight(1f)) {
            Text(name.ifEmpty { "(no name)" }, style = Type.bodyBold, color = Look.c.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, style = Type.small, color = Look.c.muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (side.isNotEmpty()) Text(side, style = Type.caption, color = Look.c.muted, modifier = Modifier.padding(start = Sp.s))
        trailing()
    }
}

// ---------------------------------------------------------------------- fields
/**
 * A text field as on the PC: its caption above, the field filled with the
 * input colour, a green line while typing in it, a note below.
 */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
          secret: Boolean = false, singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text,
          supporting: String? = null, enabled: Boolean = true, placeholder: String? = null,
          error: Boolean = false, showLabel: Boolean = true, mono: Boolean = false) {
    var shown by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(vertical = Sp.s - 2.dp)) {
        if (showLabel) Text(label, style = Type.smallBold, color = Look.c.muted, modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = singleLine, enabled = enabled,
            // The caption is the field's name for TalkBack too.
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }, shape = Radius.inner, isError = error,
            textStyle = if (mono) Type.mono.copy(fontWeight = null) else Type.lead,
            placeholder = (placeholder ?: if (showLabel) null else label)?.let { { Text(it, style = Type.lead, color = Look.c.muted) } },
            visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard,
                autoCorrectEnabled = !secret && keyboard == KeyboardType.Text),
            trailingIcon = if (secret) ({
                TextButton(onClick = { shown = !shown }) {
                    Text(if (shown) "Hide" else "Show", style = Type.label, color = Look.c.muted)
                }
            }) else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Look.c.input, unfocusedContainerColor = Look.c.input,
                disabledContainerColor = Look.c.input, errorContainerColor = Look.c.input,
                focusedBorderColor = Look.c.green, unfocusedBorderColor = Look.c.border,
                disabledBorderColor = Look.c.border, errorBorderColor = Look.c.danger,
                focusedTextColor = Look.c.text, unfocusedTextColor = Look.c.text, cursorColor = Look.c.green,
            ),
        )
        if (supporting != null) Text(supporting, style = Type.caption, color = if (error) Look.c.danger else Look.c.muted,
            modifier = Modifier.padding(top = Sp.xs))
    }
}

/** How strong a password is: five segments and the word, in the strength's colour (gui.py's meter). */
@Composable
fun Meter(label: String, fraction: Double, modifier: Modifier = Modifier) {
    if (label.isEmpty()) return
    val ink = Look.c.strength(label)
    val filled = ceil(fraction * 5).toInt().coerceIn(1, 5)
    Row(modifier.fillMaxWidth().padding(vertical = Sp.xs), verticalAlignment = Alignment.CenterVertically) {
        Text("Strength", style = Type.smallBold, color = Look.c.muted)
        Spacer(Modifier.width(Sp.m))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
            repeat(5) { i ->
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (i < filled) ink else Look.c.border))
            }
        }
        Spacer(Modifier.width(Sp.m))
        Text(label, style = Type.smallBold, color = ink)
    }
}

// ---------------------------------------------------------------------- choices
/** A tick box with its label: the whole row is the box, and the label never runs under it. */
@Composable
fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(Radius.inner).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = Sp.s), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(
            checkedColor = Look.c.green, checkmarkColor = Look.c.onAccent, uncheckedColor = Look.c.muted))
        Spacer(Modifier.width(Sp.m))
        Text(label, style = Type.body, color = Look.c.text, modifier = Modifier.weight(1f))
    }
}

/** One of several choices, as a card: the chosen one has the selected colour and a green edge. */
@Composable
fun ChoiceCard(selected: Boolean, label: String, detail: String, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = Sp.xs).clip(Radius.row)
        .background(if (selected) Look.c.selected else Look.c.card)
        .border(if (selected) 1.5.dp else 0.dp, if (selected) Look.c.green else Color.Transparent, Radius.row)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
        .padding(Sp.m), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(
            selectedColor = Look.c.green, unselectedColor = Look.c.muted))
        Spacer(Modifier.width(Sp.m))
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.bodyBold, color = Look.c.text)
            Text(detail, style = Type.small, color = Look.c.muted)
        }
    }
}

/** A radio line inside a card. */
@Composable
fun RadioRow(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(Radius.inner).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
        .padding(vertical = Sp.s), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(
            selectedColor = Look.c.green, unselectedColor = Look.c.muted))
        Spacer(Modifier.width(Sp.m))
        Text(label, style = Type.body, color = Look.c.text, modifier = Modifier.weight(1f))
    }
}

/** A setting: its name, what it does in a quiet line, and a switch; the whole row is the switch. */
@Composable
fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(Radius.inner)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = Sp.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyBold, color = Look.c.text)
            Text(detail, style = Type.small, color = Look.c.muted)
        }
        Spacer(Modifier.width(Sp.m))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedTrackColor = Look.c.green, checkedThumbColor = Look.c.onAccent, checkedBorderColor = Look.c.green,
            uncheckedTrackColor = Look.c.input, uncheckedThumbColor = Look.c.muted, uncheckedBorderColor = Look.c.border))
    }
}

/** A thin line between rows inside a card. */
@Composable
fun Rule() = Box(Modifier.fillMaxWidth().padding(vertical = Sp.s).height(1.dp).background(Look.c.border))

/** Text that may break only where a person would: after a dash of a Secret Key, between groups. */
fun breakAtDashes(text: String) = text.replace("-", "-​")
