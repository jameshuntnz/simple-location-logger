package com.example.locationlogger

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val interval = findViewById<EditText>(R.id.interval)

        val shareButton = findViewById<Button>(R.id.shareButton)
        shareButton.isEnabled = getCsvFile().exists()

        val button = findViewById<Button>(R.id.button)
        if (isServiceRunning(LocationTrackingService::class.java)) {
            button.text = "Stop"
            interval.isEnabled = false
            interval.alpha = 0.5f
            interval.hint = "Tracking in progress…"
            shareButton.isEnabled = false
        } else {
            button.text = "Start"
            interval.isEnabled = true
            interval.alpha = 1.0f
            interval.hint = "Interval (ms)"
            shareButton.isEnabled = getCsvFile().exists()
        }
        button.setOnClickListener {
            if (isServiceRunning(LocationTrackingService::class.java)) {
                val stopIntent = Intent(this, LocationTrackingService::class.java)
                stopService(stopIntent)
                button.text = "Start"
                interval.isEnabled = true
                interval.alpha = 1.0f
                interval.hint = "Interval (sec)"
                shareButton.isEnabled = getCsvFile().exists()
            } else {
                // Validate interval
                val intervalMs = interval.text.toString().toLongOrNull()
                if (intervalMs == null || intervalMs <= 0) {
                    Toast.makeText(this, "Please enter a positive interval in ms", Toast.LENGTH_SHORT).show()
                    interval.error = "Enter a positive number"
                    return@setOnClickListener
                }

                // Check permissions
                if (!hasForegroundLocationPermission()) {
                    requestForegroundLocationPermission()
                    return@setOnClickListener
                }
                if (!hasBackgroundLocationPermissionIfNeeded()) {
                    requestBackgroundLocationPermission()
                    return@setOnClickListener
                }

                val intent = Intent(this, LocationTrackingService::class.java)
                intent.putExtra("UPDATE_INTERVAL", intervalMs)
                ContextCompat.startForegroundService(this, intent)
                button.text = "Stop"
                interval.isEnabled = false
                interval.alpha = 0.5f
                interval.hint = "Tracking in progress…"
                shareButton.isEnabled = false
            }
        }
        // NOTE: Ensure AndroidManifest.xml has a <provider> with android:authorities="${applicationId}.fileprovider" and a file_paths.xml granting access to external-files-path.
        shareButton.setOnClickListener {
            val csv = getCsvFile()
            if (!csv.exists()) {
                Toast.makeText(this, "No CSV file to share yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                csv
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share locations.csv"))
        }
    }

    private fun hasForegroundLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestForegroundLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQUEST_LOCATION
        )
    }

    private fun hasBackgroundLocationPermissionIfNeeded(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBackgroundLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            REQUEST_BACKGROUND_LOCATION
        )
    }

    private fun getCsvFile(): java.io.File {
        val dir = getExternalFilesDir(null) ?: filesDir
        return java.io.File(dir, "locations.csv")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_LOCATION -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    // Foreground granted; request background if needed
                    if (!hasBackgroundLocationPermissionIfNeeded()) {
                        requestBackgroundLocationPermission()
                    } else {
                        Toast.makeText(this, "Location permission granted", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Location permission is required to start tracking", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_BACKGROUND_LOCATION -> {
                if (hasBackgroundLocationPermissionIfNeeded()) {
                    Toast.makeText(this, "Background location permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Background location permission is recommended for tracking while the app is closed", Toast.LENGTH_LONG).show()
                }
            }
        }
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

    companion object {
        private const val REQUEST_LOCATION = 1001
        private const val REQUEST_BACKGROUND_LOCATION = 1002
    }
}