package com.example.locationlogger

import android.R
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LocationTrackingService : Service() {

  private lateinit var fusedLocationClient: FusedLocationProviderClient
  private lateinit var locationRequest: LocationRequest
  private val coroutineScope = CoroutineScope(Dispatchers.IO)

  // Buffering + flushing config
  private val locationBuffer = mutableListOf<String>()
  private val bufferLock = Any()
  private var flushJob: Job? = null
  private val maxBufferSize = 50                 // flush when this many points are buffered
  private val flushIntervalMs = 30_000L          // or every 30s, whichever comes first

  override fun onCreate() {
    super.onCreate()
    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    var interval = intent?.getLongExtra("UPDATE_INTERVAL", 60L) ?: 60L
    interval *= 1000

    locationRequest = LocationRequest.Builder(
      Priority.PRIORITY_HIGH_ACCURACY, interval
    ).apply {
      setMinUpdateIntervalMillis(interval)
      setWaitForAccurateLocation(true)
      setMaxUpdateDelayMillis(interval * 2)
    }.build()

    startLiveNotification()
    startLocationUpdates()

    when (intent?.action) {
      "STOP_TRACKING" -> {
        stopSelf()
      }
    }
    // Start periodic flushing of the buffer
    if (flushJob == null) {
      flushJob = coroutineScope.launch {
        while (isActive) {
          delay(flushIntervalMs)
          flushBufferToCsv()
        }
      }
    }
    return START_STICKY
  }

  private fun startLiveNotification() {
    val channelId = "location_tracking"
    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    val channel = NotificationChannel(
      channelId,
      "Location Tracking",
      NotificationManager.IMPORTANCE_LOW
    )
    notificationManager.createNotificationChannel(channel)

    val notification = buildGolfLiveNotification(this)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    } else {
      startForeground(1, notification)
    }
  }

  private fun buildGolfLiveNotification(
    context: Context
  ): Notification {
    return NotificationCompat.Builder(context, "location_tracking")
      .setContentTitle("I am watching")
      .setContentText("tracking you ;)")
      .setSmallIcon(R.drawable.ic_menu_mylocation)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
  }

  private fun startLocationUpdates() {
    try {
      fusedLocationClient.requestLocationUpdates(
        locationRequest,
        locationCallback,
        Looper.getMainLooper()
      )
    } catch (e: SecurityException) {
      Log.e("LocationService", "Missing location permission", e)
      stopSelf()
    }
  }

  private val locationCallback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      result.lastLocation?.let { location ->
        coroutineScope.launch {
          appendLocationToBuffer(location)
          // Flush if buffer has grown large
          val shouldFlush = synchronized(bufferLock) { locationBuffer.size >= maxBufferSize }
          if (shouldFlush) flushBufferToCsv()
        }
      }
    }
  }

  private fun appendLocationToBuffer(location: Location) {
    val ts = formatIsoUtc(System.currentTimeMillis())
    val line = buildString {
      append(ts).append(',')
      append(location.latitude).append(',')
      append(location.longitude).append(',')
      append(location.accuracy).append(',')
      append(location.speed).append(',')
      append(location.bearing).append(',')
      append(location.altitude)
    }
    Log.d("LocationService", "Appending to buffer: $line")
    synchronized(bufferLock) {
      locationBuffer.add(line)
    }
  }

  private fun flushBufferToCsv() {
    val linesToWrite: List<String> = synchronized(bufferLock) {
      if (locationBuffer.isEmpty()) return
      val copy = locationBuffer.toList()
      locationBuffer.clear()
      copy
    }

    val file = getCsvFile()
    val fileExists = file.exists()

    try {
      FileWriter(file, true).use { writer ->
        if (!fileExists) {
          // Write header on first create
          writer.appendLine("timestamp,latitude,longitude,accuracy_m,speed_mps,bearing_deg,altitude_m")
        }
        for (line in linesToWrite) writer.appendLine(line)
      }
    } catch (e: Exception) {
      Log.e("LocationService", "Failed writing CSV", e)
      // If writing fails, re-buffer to avoid data loss
      synchronized(bufferLock) {
        locationBuffer.addAll(0, linesToWrite)
      }
    }
  }

  private fun getCsvFile(): File {
    // App-specific external storage so the file is user-accessible via device storage (no extra permission needed)
    val dir = getExternalFilesDir(null) ?: filesDir
    return File(dir, "locations.csv")
  }

  private fun formatIsoUtc(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date(millis))
  }

  override fun onDestroy() {
    super.onDestroy()
    fusedLocationClient.removeLocationUpdates(locationCallback)
    // Stop periodic flusher and write any remaining data
    flushJob?.cancel()
    flushJob = null
    flushBufferToCsv()
  }

  override fun onBind(intent: Intent?): IBinder? = null

}
