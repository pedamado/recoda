package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AudioMonitorScreen
import com.example.ui.AudioMonitorViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private var currentViewModel: AudioMonitorViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val viewModel: AudioMonitorViewModel = viewModel()
                currentViewModel = viewModel

                val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()

                // Keep screen awake while recording when keep awake option is enabled
                val keepAwake = engineStatus.isRecording && engineStatus.isKeepScreenAwake
                LaunchedEffect(keepAwake) {
                    if (keepAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                val permissionsToRequest = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
                    viewModel.onPermissionResult(recordGranted)
                }

                LaunchedEffect(Unit) {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    viewModel.onPermissionResult(hasPerm)
                    if (!hasPerm) {
                        permissionLauncher.launch(permissionsToRequest)
                    }
                }

                AudioMonitorScreen(
                    viewModel = viewModel,
                    onRequestPermission = {
                        permissionLauncher.launch(permissionsToRequest)
                    }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Do NOT disable or mute audio engine if recording is actively in progress,
        // so that the background ForegroundService keeps recording smoothly when the screen locks.
        if (currentViewModel?.engineStatus?.value?.isRecording != true) {
            currentViewModel?.setPassthrough(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only stop engine on finish/destroy if not recording
        if (currentViewModel?.engineStatus?.value?.isRecording != true) {
            currentViewModel?.engine?.stop()
        }
    }
}
