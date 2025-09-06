package com.example.locationlogger

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LocationLoggerTheme {
                LocationLoggerApp(
                    isServiceInitiallyRunning = isServiceRunning(LocationTrackingService::class.java),
                    getCsvFile = ::getCsvFile,
                    startLocationService = ::startLocationService,
                    stopLocationService = ::stopLocationService,
                    hasFineLocationPermission = { hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) },
                    hasBackgroundLocationPermission = { hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                )
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationService(interval: Long) {
        val intent = Intent(this, LocationTrackingService::class.java)
        intent.putExtra("UPDATE_INTERVAL", interval)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLocationService() {
        val stopIntent = Intent(this, LocationTrackingService::class.java)
        stopService(stopIntent)
    }

    private fun getCsvFile(): File {
        val dir = getExternalFilesDir(null) ?: filesDir
        return File(dir, "locations.csv")
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationLoggerApp(
    isServiceInitiallyRunning: Boolean,
    getCsvFile: () -> File,
    startLocationService: (Long) -> Unit,
    stopLocationService: () -> Unit,
    hasFineLocationPermission: () -> Boolean,
    hasBackgroundLocationPermission: () -> Boolean
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var intervalText by remember { mutableStateOf("") }
    var isServiceRunning by remember { mutableStateOf(isServiceInitiallyRunning) }
    var isShareButtonEnabled by remember { mutableStateOf(false) }

    val isCsvFilePresent = remember { mutableStateOf(getCsvFile().exists()) }

    val startButtonText = if (isServiceRunning) "Stop" else "Start"
    val isIntervalEnabled = !isServiceRunning
    val intervalHint = if (isServiceRunning) "Tracking in progress…" else "Interval (seconds)"

    LaunchedEffect(isServiceRunning, isCsvFilePresent.value) {
        isShareButtonEnabled = !isServiceRunning && isCsvFilePresent.value
    }

    val fineLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                Toast.makeText(context, "Location permission granted", Toast.LENGTH_SHORT).show()
                if (!hasBackgroundLocationPermission()) {
                    Toast.makeText(context, "Consider granting background location for tracking when app is closed.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "Location permission is required to start tracking", Toast.LENGTH_LONG).show()
            }
        }
    )

    val backgroundLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                Toast.makeText(context, "Background location permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Background location permission is recommended for tracking while the app is closed", Toast.LENGTH_LONG).show()
            }
        }
    )

    // Register a receiver only while this Composable is active (app open)
    androidx.compose.runtime.DisposableEffect(Unit) {
        val filter = IntentFilter("com.example.locationlogger.ACTION_LOCATION_LOGGED")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.locationlogger.ACTION_LOCATION_LOGGED") {
                    scope.launch { snackbarHostState.showSnackbar("Location logged") }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Location Logger") })
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = intervalText,
                onValueChange = { intervalText = it },
                label = { Text(intervalHint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = isIntervalEnabled,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (isServiceRunning) {
                        stopLocationService()
                        isServiceRunning = false
                        val exists = getCsvFile().exists()
                        isCsvFilePresent.value = exists
                        isShareButtonEnabled = exists
                    } else {
                        val interval = intervalText.toLongOrNull()
                        if (interval == null || interval <= 0) {
                            Toast.makeText(context, "Please enter a positive interval in seconds", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        if (!hasFineLocationPermission()) {
                            fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            return@Button
                        }
                        if (hasFineLocationPermission() && !hasBackgroundLocationPermission()) {
                            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }

                        startLocationService(interval)
                        isServiceRunning = true
                        isShareButtonEnabled = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(startButtonText)
            }

            Button(
                onClick = {
                    val csvFile = getCsvFile()
                    if (!csvFile.exists()) {
                        Toast.makeText(context, "No CSV file to share yet", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val uri: Uri = FileProvider.getUriForFile(
                        context,
                        "${context.applicationContext.packageName}.fileprovider",
                        csvFile
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share locations.csv"))
                },
                enabled = isShareButtonEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Share CSV")
            }

            Button(
                onClick = {
                    val csvFile = getCsvFile()
                    if (!csvFile.exists()) {
                        Toast.makeText(context, "No CSV file to delete", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val deleted = csvFile.delete()
                    if (deleted) {
                        Toast.makeText(context, "CSV deleted", Toast.LENGTH_SHORT).show()
                        // Update state after deletion
                        isCsvFilePresent.value = false
                        isShareButtonEnabled = false
                    } else {
                        Toast.makeText(context, "Failed to delete CSV", Toast.LENGTH_LONG).show()
                    }
                },
                enabled = !isServiceRunning && isCsvFilePresent.value,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete CSV")
            }
        }
    }
}

// Compose Material3 theme in Kotlin
@Composable
fun LocationLoggerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}
