package dev.aarso.ui.wire

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.aarso.ui.hyle.HyleCard
import dev.aarso.ui.hyle.HyleChip
import dev.aarso.ui.hyle.HyleField

/**
 * Shared **wire** atoms — boxes, buttons, fields, now reskinned onto the real Hyle components
 * ([HyleCard]/[HyleChip]/[HyleField]) so every screen still built against this small vocabulary
 * (Remote, Terminal, Audit, FreeTiers) picks up the design system without a per-call-site rewrite.
 * Material/state is shown by the control itself (a selected chip is filled), not by status words
 * (THE LAW).
 */

@Composable
fun WireBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    HyleCard(modifier, content = content)
}

@Composable
fun WireButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    HyleChip(selected = selected, onClick = onClick, label = label, enabled = enabled)
}

@Composable
fun WireField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    secret: Boolean = false,
) {
    HyleField(
        value = value,
        onValueChange = onChange,
        label = label,
        modifier = modifier.fillMaxWidth(),
        singleLine = !secret,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}
