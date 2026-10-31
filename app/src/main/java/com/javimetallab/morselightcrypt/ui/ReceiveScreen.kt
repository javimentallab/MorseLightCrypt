package com.javimetallab.morselightcrypt.ui

import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.javimetallab.morselightcrypt.R
import com.javimetallab.morselightcrypt.crypto.SecretCodec
import com.javimetallab.morselightcrypt.morse.MorseTiming
import com.javimetallab.morselightcrypt.receive.LightMeter
import com.javimetallab.morselightcrypt.receive.Microphone
import com.javimetallab.morselightcrypt.receive.ROI_FRACTION
import com.javimetallab.morselightcrypt.receive.ReceiveState
import com.javimetallab.morselightcrypt.receive.ReceiveViewModel
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Amber of the "hold it steadier" warning: still reading, unlike the red one. */
private val ShakyColor = Color(0xFFFFC94D)

/** Pause after the last pinch before the zoom counts as chosen. */
private const val ZOOM_SETTLE_MS = 700L

/** Time the auto exposure gets to settle before it is locked. */
private const val EXPOSURE_SETTLE_MS = 1500L

/**
 * Points the camera at a blinking light and reads it, or with [sound] listens
 * for beeps with the microphone. With [encrypted] the
 * received text can then be decrypted with a password.
 */
@Composable
fun ReceiveScreen(
    model: ReceiveViewModel,
    encrypted: Boolean,
    wpm: Int,
    onWpmChange: (Int) -> Unit,
    hasCameraPermission: Boolean,
    onRequestCamera: () -> Unit,
    sound: Boolean,
    onSoundChange: (Boolean) -> Unit,
    hasMicPermission: Boolean,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(wpm) { model.hintUnitMs = MorseTiming.unitMs(wpm).toDouble() }
    // Before anything is drawn, so a switch never feeds the wrong engine.
    model.soundMode = sound

    val canSense = if (sound) hasMicPermission else hasCameraPermission
    if (if (sound) model.listening else hasCameraPermission) KeepScreenOn()
    val state by model.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var recalibrations by remember { mutableIntStateOf(0) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var micError by remember(sound) { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // The microphone is open only while listening: nothing is heard while paused.
    if (sound && hasMicPermission && model.listening) {
        LaunchedEffect(Unit) {
            micError = null
            try {
                Microphone.listen(context, model::onTone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                micError = context.getString(R.string.mic_open_failed, e.message ?: e.javaClass.simpleName)
                model.stopListening()
            }
        }
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Half the width each: where a language needs two lines, both wrap alike.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !sound, onClick = { onSoundChange(false) })
                Text(stringResource(R.string.light_signal))
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = sound, onClick = { onSoundChange(true) })
                Text(stringResource(R.string.sound_signal))
            }
        }

        // Without the camera or microphone a received message can still be typed in by hand.
        if (sound && hasMicPermission) {
            SignalGraph(
                state,
                modifier = Modifier.fillMaxWidth().height(200.dp),
                label = state.toneHz?.takeIf { model.listening && state.hasSignal }
                    ?.let { stringResource(R.string.tone_hz, it) }
            )
        } else if (!sound && hasCameraPermission) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CameraView(
                    model = model,
                    isOn = state.isOn,
                    recalibrations = recalibrations,
                    onError = { cameraError = it },
                    modifier = Modifier
                        .height(300.dp)
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(16.dp))
                )
                // Over the bottom of the viewfinder, where the eye already is.
                val warning = when {
                    state.moving -> R.string.banner_moving to MaterialTheme.colorScheme.error
                    state.shaky -> R.string.banner_shaky to ShakyColor
                    else -> null
                }
                warning?.let { (text, color) ->
                    Text(
                        stringResource(text),
                        color = Color.Black,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .background(color, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (canSense) {
            // Under the viewfinder, where the thumb already is.
            Button(
                onClick = { if (model.listening) model.stopListening() else model.startListening() },
                colors = if (model.listening) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(if (model.listening) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (model.listening) R.string.stop_receiving else R.string.start_receiving))
            }

            val error = if (sound) micError else cameraError
            Text(
                text = when {
                    error != null -> error
                    sound && !model.listening -> stringResource(R.string.paused_sound)
                    sound && !state.hasSignal -> stringResource(R.string.listen_for_tone)
                    sound && state.decoded.wpm == null -> stringResource(R.string.tone_found)
                    !model.listening -> stringResource(R.string.paused)
                    !sound && model.calibrating -> stringResource(R.string.calibrating)
                    state.moving -> stringResource(R.string.moving)
                    !state.hasSignal -> stringResource(R.string.aim_at_light)
                    state.decoded.wpm != null -> stringResource(R.string.receiving, state.decoded.wpm ?: 0)
                    else -> stringResource(R.string.light_found)
                },
                color = if (error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge
            )

            if (!sound) SignalGraph(state)

            SpeedSlider(label = R.string.expected_speed, wpm = wpm, onWpmChange = onWpmChange)
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (sound) {
                        Text(stringResource(R.string.mic_permission))
                        Button(onClick = onRequestMic) { Text(stringResource(R.string.allow_mic)) }
                    } else {
                        Text(stringResource(R.string.camera_permission))
                        Button(onClick = onRequestCamera) { Text(stringResource(R.string.allow_camera)) }
                    }
                }
            }
        }

        // Earlier sessions plus the one being read, each on its own line.
        val live = if (model.listening) state.decoded.text.trim() else ""
        val allText = listOf(model.received.trimEnd(), live).filter { it.isNotEmpty() }.joinToString("\n")
        val receivedStyle = if (encrypted) MorseStyle else MaterialTheme.typography.titleLarge

        if (model.listening) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val morse = (state.decoded.morse + " " + state.decoded.pending).trim()
                    if (morse.isNotEmpty()) {
                        Text(morse.takeLast(120), style = MorseStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        allText.ifEmpty { stringResource(R.string.received_empty) },
                        style = if (allText.isEmpty()) MaterialTheme.typography.bodyMedium else receivedStyle
                    )
                }
            }
        } else {
            // Paused: the text can be fixed by hand, stray letters deleted.
            OutlinedTextField(
                value = model.received,
                onValueChange = { model.received = it },
                label = { Text(stringResource(R.string.received_label)) },
                placeholder = { Text(stringResource(R.string.received_empty)) },
                textStyle = receivedStyle,
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (encrypted) {
            PasswordField(
                value = model.password,
                onValueChange = { model.password = it },
                // Touching the phone to type would be read as flashes: stop first.
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) model.stopListening() }
            )
            Button(
                onClick = model::decrypt,
                enabled = allText.isNotBlank() && model.password.isNotEmpty() && !model.decrypting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (model.decrypting) R.string.decrypting else R.string.decrypt))
            }
            val result = model.decryption
            when {
                model.decryptFailed -> Text(
                    stringResource(R.string.decrypt_failed),
                    color = MaterialTheme.colorScheme.error
                )
                result != null -> DecryptionCard(result)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = model::clear) { Text(stringResource(R.string.clear)) }
            OutlinedButton(
                onClick = {
                    val opened = model.decryption?.lines?.mapNotNull { it.text }?.joinToString("\n")
                    val text = if (encrypted && !opened.isNullOrEmpty()) opened else allText
                    clipboard.setText(AnnotatedString(text))
                },
                enabled = allText.isNotEmpty()
            ) { Text(stringResource(R.string.copy)) }
            if (!sound && hasCameraPermission) {
                OutlinedButton(onClick = { recalibrations++ }) { Text(stringResource(R.string.recalibrate)) }
            }
        }

        if (canSense) {
            Text(
                stringResource(if (sound) R.string.receive_tips_sound else R.string.receive_tips),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Camera preview plus the square that is measured. The preview box is 3:4 like
 * the stream, so nothing is cropped and the square on screen covers exactly
 * the pixels [LightMeter] reads.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun CameraView(
    model: ReceiveViewModel,
    isOn: Boolean,
    recalibrations: Int,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // Zooming changes the whole picture, which the meter would read as
    // blinking: once the fingers rest, the exposure and meter start over.
    var zoom by remember { mutableFloatStateOf(1f) }
    var settledZoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(zoom) {
        delay(ZOOM_SETTLE_MS)
        settledZoom = zoom
    }

    DisposableEffect(Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            provider = providerFuture.get()
            val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Small frames are plenty to measure one bright spot, and cheap.
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(fourThree)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // A steady 30 fps: in the dark auto exposure would otherwise slow the
            // frame rate down, and every frame is a slice of time.
            // Optical stabilisation, where the lens has it: at high zoom a hand's
            // tremor would otherwise sweep the lamp across the measured square.
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                .setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            val meter = LightMeter()
            val analysis = analysisBuilder.build().also {
                it.setAnalyzer(executor) { image ->
                    try {
                        // While the exposure settles every pixel changes at once;
                        // that is not blinking, so the meter starts over after.
                        if (model.calibrating) meter.reset()
                        else model.onFrame(meter.measure(image), image.imageInfo.timestamp)
                    } finally {
                        image.close()
                    }
                }
            }

            try {
                provider?.unbindAll()
                camera = provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                onError(context.getString(R.string.camera_open_failed, e.message ?: ""))
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            camera = null
        }
    }

    // Auto exposure would dim the frame each time the light comes on and undo
    // the contrast we measure, so it is set as dark as allowed and then locked.
    LaunchedEffect(camera, recalibrations, settledZoom) {
        val cam = camera ?: return@LaunchedEffect
        model.startCalibration()
        val control = Camera2CameraControl.from(cam.cameraControl)
        control.setCaptureRequestOptions(aeLock(false))
        val exposure = cam.cameraInfo.exposureState
        if (exposure.isExposureCompensationSupported) {
            cam.cameraControl.setExposureCompensationIndex(exposure.exposureCompensationRange.lower)
        }
        delay(EXPOSURE_SETTLE_MS)
        control.setCaptureRequestOptions(aeLock(true))
        model.endCalibration()
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTransformGestures { _, _, scale, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val range = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        val next = (zoom * scale).coerceIn(range.minZoomRatio, range.maxZoomRatio)
                        if (next != zoom) {
                            zoom = next
                            cam.cameraControl.setZoomRatio(next)
                        }
                    }
                }
        )
        if (zoom > 1.05f) {
            Text(
                "%.1f×".format(zoom),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(ROI_FRACTION)
                .aspectRatio(1f)
                .border(2.dp, if (isOn) LampColor else Color.White, RoundedCornerShape(4.dp))
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(18.dp)
                .background(if (isOn) LampColor else Color.Black.copy(alpha = 0.5f), CircleShape)
                .border(2.dp, LampColor, CircleShape)
        )
    }
}

@OptIn(ExperimentalCamera2Interop::class)
private fun aeLock(locked: Boolean) = CaptureRequestOptions.Builder()
    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
    .build()

/**
 * One row per received line, in order: what it decrypted to, or that it would
 * not open. Nothing is merged or hidden, even when lines say the same.
 */
@Composable
private fun DecryptionCard(result: SecretCodec.Result) {
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                result.lines.forEachIndexed { index, line ->
                    val opened = line.text
                    if (opened != null) {
                        Text(opened, style = MaterialTheme.typography.headlineSmall)
                    } else {
                        Text(
                            stringResource(R.string.line_not_opened, index + 1, line.received),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                result.majority?.let {
                    Text(
                        stringResource(R.string.majority_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(it, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

/**
 * Brightness (or loudness of the tone) over the last few seconds, with the on
 * threshold. By ear it stands in for the viewfinder, with the pitch as [label].
 */
@Composable
private fun SignalGraph(
    state: ReceiveState,
    modifier: Modifier = Modifier.fillMaxWidth().height(72.dp),
    label: String? = null
) {
    val line = if (state.isOn) LampColor else MaterialTheme.colorScheme.primary
    val threshold = MaterialTheme.colorScheme.secondary
    Box(modifier) {
    Canvas(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val levels = state.levels
        if (levels.size < 2) return@Canvas
        fun y(level: Float) = size.height * (1f - level / 255f)
        val step = size.width / (levels.size - 1)

        val path = Path().apply {
            moveTo(0f, y(levels[0]))
            for (i in 1 until levels.size) lineTo(i * step, y(levels[i]))
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))

        if (state.hasSignal) {
            val ty = y(state.threshold)
            drawLine(threshold, Offset(0f, ty), Offset(size.width, ty), strokeWidth = 1.dp.toPx())
        }
    }
    label?.let {
        Text(
            it,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
    }
}
