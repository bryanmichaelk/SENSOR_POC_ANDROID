package com.bryan.sensorpoc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class SensorCollectionService : Service(), SensorEventListener, LocationListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var recorder: SessionRecorder
    private var source: MainActivity.Source? = null
    private var previousTimestamp = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        recorder = SessionRecorder(filesDir)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_SOURCE_ID))
            ACTION_STOP -> stop("user_stopped_background_test")
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start(id: String?) {
        val selected = MainActivity.Source.fromId(id) ?: return
        if (!isEligible(selected)) {
            publish("Cannot start ${selected.title}: missing required access.")
            stopSelf()
            return
        }
        source = selected
        previousTimestamp = 0L
        recorder.start(selected, Build.MANUFACTURER, Build.MODEL, "foreground_service")
        if (Build.VERSION.SDK_INT >= 34) {
            val type = when (selected) {
                MainActivity.Source.LOCATION -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                MainActivity.Source.LIGHT, MainActivity.Source.PROXIMITY -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            }
            startForeground(NOTIFICATION_ID, notification("Testing ${selected.title}"), type)
        } else {
            startForeground(NOTIFICATION_ID, notification("Testing ${selected.title}"))
        }
        if (selected == MainActivity.Source.LOCATION) {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 0f, this)
        } else {
            val sensor = selected.sensorType?.let(sensorManager::getDefaultSensor)
            if (sensor == null) stop("sensor_unavailable") else sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        publish("Background test started: ${selected.title}")
    }

    private fun isEligible(source: MainActivity.Source): Boolean {
        if (source.permission != null && ContextCompat.checkSelfPermission(this, source.permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        if (source == MainActivity.Source.LOCATION && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return source.sensorType == null || sensorManager.getDefaultSensor(source.sensorType) != null
    }

    private fun stop(reason: String) {
        sensorManager.unregisterListener(this)
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        recorder.stop(reason)
        publish("Background test stopped: $reason")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gapMs = if (previousTimestamp == 0L) 0 else (event.timestamp - previousTimestamp) / 1_000_000
        previousTimestamp = event.timestamp
        recorder.append(mapOf(
            "sensorTimestampNs" to event.timestamp,
            "accuracy" to event.accuracy,
            "values" to event.values.joinToString(prefix = "[", postfix = "]"),
            "gapMs" to gapMs
        ))
        publish("Testing ${source?.title}: latest sensor event")
    }

    override fun onLocationChanged(location: Location) {
        recorder.append(mapOf(
            "provider" to (location.provider ?: "unknown"),
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracyM" to location.accuracy,
            "altitudeM" to location.altitude,
            "speedMps" to location.speed,
            "bearingDegrees" to location.bearing,
            "locationTimeEpochMs" to location.time
        ))
        publish("Testing location: ${location.provider}, ±${location.accuracy}m")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Sensor feasibility tests", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setContentTitle("Sensor API PoC")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun publish(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName).putExtra(EXTRA_MESSAGE, text))
    }

    companion object {
        private const val CHANNEL_ID = "sensor_tests"
        private const val NOTIFICATION_ID = 1101
        const val ACTION_START = "com.bryan.sensorpoc.START_BACKGROUND_TEST"
        const val ACTION_STOP = "com.bryan.sensorpoc.STOP_BACKGROUND_TEST"
        const val ACTION_UPDATE = "com.bryan.sensorpoc.BACKGROUND_TEST_UPDATE"
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_MESSAGE = "message"

        fun start(context: Context, source: MainActivity.Source) {
            ContextCompat.startForegroundService(context, Intent(context, SensorCollectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_ID, source.id)
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SensorCollectionService::class.java).apply { action = ACTION_STOP })
        }
    }
}
