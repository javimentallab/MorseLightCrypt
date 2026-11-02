package com.javimetallab.morselightcrypt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.javimetallab.morselightcrypt.MAX_WPM
import com.javimetallab.morselightcrypt.MIN_WPM
import com.javimetallab.morselightcrypt.R
import com.javimetallab.morselightcrypt.morse.MorseTiming

@Composable
fun SpeedSlider(
    label: Int,
    wpm: Int,
    onWpmChange: (Int) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            stringResource(label, wpm, MorseTiming.unitMs(wpm)),
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = wpm.toFloat(),
            onValueChange = { onWpmChange(it.toInt()) },
            valueRange = MIN_WPM.toFloat()..MAX_WPM.toFloat(),
            steps = MAX_WPM - MIN_WPM - 1,
            enabled = enabled
        )
    }
}

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.password)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.hide_password else R.string.show_password
                    )
                )
            }
        },
        modifier = modifier
    )
}

/** Keeps the screen awake while this is on screen: a dimmed screen ends a transmission. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * The privacy policy, as text: the app has no Internet permission, and a policy is the last thing
 * that should change that. Keep it in step with PRIVACY.md.
 */
@Composable
fun PrivacyPolicy(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Text(stringResource(R.string.privacy_policy), style = MaterialTheme.typography.bodySmall)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.privacy_policy)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.privacy_text))
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }
}
