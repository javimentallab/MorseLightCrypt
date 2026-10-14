package com.javimetallab.morselightcrypt

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.javimetallab.morselightcrypt.receive.ReceiveViewModel
import com.javimetallab.morselightcrypt.send.SendViewModel
import com.javimetallab.morselightcrypt.ui.MorseLightCryptTheme
import com.javimetallab.morselightcrypt.ui.ReceiveScreen
import com.javimetallab.morselightcrypt.ui.SendScreen

class MainActivity : ComponentActivity() {

    private var cameraGranted by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        enableEdgeToEdge()
        setContent {
            MorseLightCryptTheme {
                MorseLightCryptApp(
                    hasCameraPermission = cameraGranted,
                    onRequestCamera = { requestCamera.launch(Manifest.permission.CAMERA) }
                )
            }
        }
    }
}

private enum class Section(val label: Int, val icon: ImageVector, val encrypted: Boolean, val sends: Boolean) {
    SEND(R.string.tab_send, Icons.Filled.FlashOn, encrypted = false, sends = true),
    RECEIVE(R.string.tab_receive, Icons.Filled.Visibility, encrypted = false, sends = false),
    SEND_SECRET(R.string.tab_send_secret, Icons.Filled.Lock, encrypted = true, sends = true),
    RECEIVE_SECRET(R.string.tab_receive_secret, Icons.Filled.LockOpen, encrypted = true, sends = false)
}

@Composable
private fun MorseLightCryptApp(
    hasCameraPermission: Boolean,
    onRequestCamera: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val section = Section.entries[tab]
    val settings: SettingsViewModel = viewModel()

    // Each section keeps its own message and password while the app lives.
    val send: SendViewModel = viewModel(key = "send")
    val receive: ReceiveViewModel = viewModel(key = "receive")
    val sendSecret: SendViewModel = viewModel(key = "send-secret")
    val receiveSecret: ReceiveViewModel = viewModel(key = "receive-secret")

    SecureWindow(enabled = section.encrypted)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            TabRow(selectedTabIndex = tab) {
                Section.entries.forEachIndexed { index, s ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        // A size down from the default, so "Descifrar" fits a quarter of a phone.
                        text = {
                            Text(
                                stringResource(s.label),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        icon = { Icon(s.icon, contentDescription = null) }
                    )
                }
            }

            val modifier = Modifier.fillMaxSize()
            // A fresh screen per section, so the camera feeds the right model.
            key(section) {
                if (section.sends) {
                    SendScreen(
                        model = if (section.encrypted) sendSecret else send,
                        encrypted = section.encrypted,
                        wpm = settings.wpm,
                        onWpmChange = settings::updateWpm,
                        modifier = modifier
                    )
                } else {
                    ReceiveScreen(
                        model = if (section.encrypted) receiveSecret else receive,
                        encrypted = section.encrypted,
                        wpm = settings.wpm,
                        onWpmChange = settings::updateWpm,
                        hasCameraPermission = hasCameraPermission,
                        onRequestCamera = onRequestCamera,
                        modifier = modifier
                    )
                }
            }
        }
    }
}

/** On the encrypted tabs: no screenshots, no thumbnail in recents. */
@Composable
private fun SecureWindow(enabled: Boolean) {
    val window = (LocalContext.current as Activity).window
    DisposableEffect(enabled) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
}
