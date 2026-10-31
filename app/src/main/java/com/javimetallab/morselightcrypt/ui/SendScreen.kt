package com.javimetallab.morselightcrypt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.javimetallab.morselightcrypt.R
import com.javimetallab.morselightcrypt.crypto.SecretCodec
import com.javimetallab.morselightcrypt.morse.MorseCode
import com.javimetallab.morselightcrypt.morse.MorseTiming
import com.javimetallab.morselightcrypt.send.SendViewModel

/**
 * Types a message and sends it by torch, by beeps or both. With [encrypted] the message
 * is encrypted with a password first and the ciphertext is what gets flashed.
 */
@Composable
fun SendScreen(
    model: SendViewModel,
    encrypted: Boolean,
    wpm: Int,
    onWpmChange: (Int) -> Unit,
    light: Boolean,
    onLightChange: (Boolean) -> Unit,
    sound: Boolean,
    onSoundChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Leaving the tab ends the transmission: the receiver tabs need the camera
    // and the torch belongs to it.
    DisposableEffect(model) { onDispose { model.stop() } }
    if (model.sending) KeepScreenOn()

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (encrypted) {
            PasswordField(
                value = model.password,
                onValueChange = { model.password = it },
                enabled = !model.sending,
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedTextField(
            value = model.text,
            onValueChange = { model.text = it },
            label = { Text(stringResource(R.string.message)) },
            minLines = 3,
            enabled = !model.sending,
            modifier = Modifier.fillMaxWidth()
        )

        // Always laid out, blank when there is nothing to say, so the buttons
        // below do not jump the moment the first letter is typed.
        Text(
            text = when {
                model.text.isBlank() -> ""
                encrypted -> {
                    stringResource(R.string.encrypted_length, SecretCodec.encryptedLength(model.text))
                }
                else -> {
                    val units = MorseTiming.schedule(model.text).sumOf { it.units }
                    val seconds = (units * MorseTiming.unitMs(wpm) + 999) / 1000
                    stringResource(R.string.send_duration, seconds)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SpeedSlider(
            label = R.string.speed_label,
            wpm = wpm,
            onWpmChange = onWpmChange,
            enabled = !model.sending
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = light && model.hasFlash,
                onCheckedChange = onLightChange,
                enabled = !model.sending && model.hasFlash
            )
            Text(stringResource(R.string.by_light))
            Spacer(Modifier.width(16.dp))
            Checkbox(
                checked = sound,
                onCheckedChange = onSoundChange,
                enabled = !model.sending
            )
            Text(stringResource(R.string.by_sound))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = model.repeat,
                onCheckedChange = { model.repeat = it },
                enabled = !model.sending
            )
            Text(stringResource(R.string.repeat))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { if (model.sending) model.stop() else model.start(wpm, encrypted, light, sound) },
                enabled = model.sending || (light && model.hasFlash) || sound,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(
                        when {
                            model.encrypting -> R.string.encrypting
                            model.sending -> R.string.stop
                            encrypted -> R.string.encrypt_and_send
                            else -> R.string.send
                        }
                    )
                )
            }
            Lamp(lit = model.lit)
        }

        if (!model.hasFlash) {
            Text(stringResource(R.string.no_flash), color = MaterialTheme.colorScheme.error)
        }
        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Progress(model, encrypted)
    }
}

/** What goes out, with the letter being flashed highlighted. */
@Composable
private fun Progress(model: SendViewModel, encrypted: Boolean) {
    val outgoing = model.outgoing
    val index = model.currentIndex
    val highlight = SpanStyle(background = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                model.sending && outgoing.isNotEmpty() && index in outgoing.indices -> {
                    Text(
                        buildAnnotatedString {
                            append(outgoing.substring(0, index))
                            withStyle(highlight) { append(outgoing[index]) }
                            append(outgoing.substring(index + 1))
                        },
                        style = MorseStyle
                    )
                    Text(
                        "${outgoing[index]}  ${MorseCode.codeOf(outgoing[index]).orEmpty()}",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }

                encrypted && model.cipherText.isNotEmpty() -> {
                    Text(stringResource(R.string.last_ciphertext), style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(model.cipherText, style = MorseStyle) }
                }

                !encrypted && model.text.isNotBlank() -> {
                    Text(MorseCode.toMorse(model.text), style = MorseStyle)
                }

                else -> Text(
                    stringResource(if (encrypted) R.string.cipher_preview_empty else R.string.morse_preview_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Mirrors the torch on screen, so you can see what is being sent. */
@Composable
private fun Lamp(lit: Boolean) {
    Box(
        Modifier
            .size(36.dp)
            .background(if (lit) LampColor else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(2.dp, LampColor, CircleShape)
    )
}
