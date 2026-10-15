package com.bryan.sensorpoc

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var content: LinearLayout
    private lateinit var header: TextView
    private lateinit var status: TextView
    private lateinit var latest: TextView
    private lateinit var recorder: SessionRecorder
    private var activeSource: Source? = null
    private var eventCount = 0L
    private var previousTimestamp = 0L
    private var screenReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var pendingExport: File? = null
    private var pendingExportFormat = ExportFormat.JSONL

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        status.text = if (granted) "Permission granted. Start test." else "Permission denied."
        renderDashboard()
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val source = pendingExport
        val destination = result.data?.data
        if (result.resultCode == RESULT_OK && source != null && destination != null) {
            contentResolver.openOutputStream(destination)?.use { output ->
                if (pendingExportFormat == ExportFormat.JSONL) source.inputStream().use { it.copyTo(output) }
                else output.writer().use { it.write(recorder.toCsv(source)) }
            }
            if (::status.isInitialized) status.text = "Exported ${source.name}."
        } else if (::status.isInitialized) {
            status.text = "Export cancelled."
        }
        pendingExport = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        recorder = SessionRecorder(filesDir)
        renderDashboard()
    }

    override fun onStop() {
        super.onStop()
        // Direct collection is foreground-only. Explicit foreground-service tests continue independently.
        if (activeSource?.sensorType != null) stopTest("activity_backgrounded")
    }

    override fun onDestroy() {
        stopTest("activity_destroyed")
        super.onDestroy()
    }

    private fun renderDashboard() {
        sensorManager.unregisterListener(this)
        activeSource = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        content = rootLayout()
        header.text = "Sensor API PoC"
        content.addView(title("Capability dashboard"))
        content.addView(note("Local-only experiments. Tap source for access state, live test, and export."))
        content.addView(deviceSummary())
        content.addView(sectionLabel("CANDIDATE SOURCES"))
        sources.forEach { source -> content.addView(sourceRow(source)) }
        content.addView(sectionLabel("RECENT SESSIONS"))
        val sessions = recorder.sessionFiles().take(5)
        if (sessions.isEmpty()) content.addView(note("No saved sessions. Run a supported test to create local JSONL data."))
        else sessions.forEach { file -> content.addView(sessionRow(file)) }
        content.addView(button("Delete all local sessions", false) { recorder.deleteAll(); renderDashboard() })
        setContentView(content.tag as View)
    }

    private fun renderDetail(source: Source) {
        sensorManager.unregisterListener(this)
        activeSource = source
        content = rootLayout()
        header.text = source.title
        content.addView(button("Back to dashboard", false) { stopTest("back"); renderDashboard() })
        content.addView(title(source.title))
        content.addView(note(source.description))
        content.addView(metadata(source))
        status = note(accessMessage(source)).apply { setPadding(0, dp(12), 0, dp(6)) }
        content.addView(status)
        latest = mono("No test running.")
        content.addView(latest)

        if (source == Source.USAGE_STATS) {
            content.addView(button("Open Usage Access settings", true) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })
            content.addView(button("Query last 24 hours", false) { queryUsageStats() })
            content.addView(button("Schedule one background usage snapshot", false) { scheduleUsageSnapshot() })
        } else if (source == Source.CALL_LOG) {
            content.addView(note("Call-log reading is intentionally not implemented. Google Play restricts this permission to eligible default Phone or Assistant apps and approved exceptions."))
        } else {
            source.permission?.let { permission ->
                if (!hasPermission(permission)) content.addView(button("Grant ${source.permissionLabel}", true) {
                    permissionLauncher.launch(permission)
                })
            }
            content.addView(button("Start foreground test", true) { startTest(source) })
            if (source.backgroundTestSupported) {
                content.addView(button("Start foreground-service test", true) { startBackgroundTest(source) })
                content.addView(button("Stop foreground-service test", false) { SensorCollectionService.stop(this); status.text = "Stopping foreground-service test." })
            }
            content.addView(button("Stop test", false) { stopTest("user_stopped") })
        }
        content.addView(button("Export newest JSONL session", false) { exportNewestSession(ExportFormat.JSONL) })
        content.addView(button("Export newest CSV session", false) { exportNewestSession(ExportFormat.CSV) })
        content.addView(sectionLabel("DOCUMENTED LIMIT"))
        content.addView(note(source.limit))
        setContentView(content.tag as View)
    }

    private fun rootLayout(): LinearLayout {
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.surface)) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(32))
        }
        header = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.forest))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
        }
        column.addView(header)
        scroll.addView(column)
        return column.apply { tag = scroll }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 28f
        setTextColor(color(R.color.ink))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        letterSpacing = 0.08f
        setTextColor(color(R.color.muted))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(color(R.color.muted))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun mono(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = android.graphics.Typeface.MONOSPACE
        setTextColor(color(R.color.ink))
        setBackgroundColor(color(R.color.surface_variant))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun button(text: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        minHeight = dp(48)
        setTextColor(if (primary) color(R.color.surface) else color(R.color.forest_dark))
        setBackgroundColor(if (primary) color(R.color.forest) else color(R.color.surface_variant))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(10)
        }
    }

    private fun sourceRow(source: Source) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(color(R.color.surface_variant))
        contentDescription = "Open ${source.title} test"
        isClickable = true
        isFocusable = true
        setOnClickListener { renderDetail(source) }
        addView(TextView(this@MainActivity).apply {
            text = source.title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        addView(TextView(this@MainActivity).apply {
            text = "${availability(source)}  ·  ${source.status}"
            textSize = 13f
            setTextColor(statusColor(source))
            setPadding(0, dp(4), 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun sessionRow(file: File) = TextView(this).apply {
        text = file.name.removeSuffix(".jsonl")
        textSize = 14f
        setTextColor(color(R.color.forest_dark))
        isClickable = true
        isFocusable = true
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(color(R.color.surface_variant))
        setOnClickListener { renderSession(file) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun deviceSummary() = mono(
        "DEVICE ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "ANDROID ${Build.VERSION.RELEASE}  API ${Build.VERSION.SDK_INT}\n" +
            "SENSORS ${sensorManager.getSensorList(Sensor.TYPE_ALL).size} detected"
    ).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }

    private fun metadata(source: Source) = mono(
        "API  ${source.api}\n" +
            "UNIT ${source.unit}\n" +
            "MODE ${source.collectionMode}\n" +
            "HARDWARE ${if (source.sensorType == null) "not required" else hardwareName(source)}"
    ).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }

    private fun hardwareName(source: Source): String {
        val sensor = source.sensorType?.let(sensorManager::getDefaultSensor) ?: return "not required"
        return "${sensor.name} (${sensor.vendor})"
    }

    private fun availability(source: Source): String = when {
        source == Source.CALL_LOG -> "Restricted"
        source == Source.USAGE_STATS && !hasUsageAccess() -> "Usage Access required"
        source.permission != null && !hasPermission(source.permission) -> "Permission required"
        source.sensorType != null && sensorManager.getDefaultSensor(source.sensorType) == null -> "Hardware missing"
        else -> "Ready"
    }

    private fun statusColor(source: Source) = when {
        source == Source.CALL_LOG -> color(R.color.danger)
        availability(source) == "Ready" -> color(R.color.forest)
        else -> color(R.color.warning)
    }

    private fun accessMessage(source: Source) = when {
        source == Source.CALL_LOG -> "Restricted: no call-log test in standard PoC build."
        source == Source.USAGE_STATS && !hasUsageAccess() -> "Usage Access disabled. Grant through Android Settings."
        source.permission != null && !hasPermission(source.permission) -> "${source.permissionLabel} is required before test."
        source.sensorType != null && sensorManager.getDefaultSensor(source.sensorType) == null -> "Unsupported: this device has no matching sensor."
        else -> "Ready. Foreground test stores local JSONL session data."
    }

    private fun startTest(source: Source) {
        if (source.permission != null && !hasPermission(source.permission)) {
            status.text = "Cannot start: ${source.permissionLabel} denied."
            return
        }
        if (source.sensorType != null && sensorManager.getDefaultSensor(source.sensorType) == null) {
            status.text = "Cannot start: sensor unavailable."
            return
        }
        stopTest("replaced")
        activeSource = source
        eventCount = 0
        previousTimestamp = 0L
        recorder.start(source, Build.MANUFACTURER, Build.MODEL)
        when (source) {
            Source.BATTERY -> startBatteryTest()
            Source.SCREEN -> startScreenTest()
            Source.LOCATION -> startLocationTest()
            else -> sensorManager.registerListener(this, sensorManager.getDefaultSensor(source.sensorType!!), SensorManager.SENSOR_DELAY_NORMAL)
        }
        status.text = "Running foreground test. Leaving app stops this session."
        latest.text = "Waiting for first event..."
    }

    private fun startBackgroundTest(source: Source) {
        if (source.permission != null && !hasPermission(source.permission)) {
            status.text = "Cannot start: ${source.permissionLabel} denied."
            return
        }
        if (source == Source.LOCATION && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            status.text = "Grant precise location, then start foreground-service test again."
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            status.text = "Grant notifications, then start foreground-service test again."
            return
        }
        SensorCollectionService.start(this, source)
        status.text = "Foreground-service test started. Use system notification or Stop button to end it."
    }

    private fun startBatteryTest() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val charge = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                record(mapOf("level" to level, "scale" to scale, "status" to charge))
                latest.text = "level=$level/$scale status=$charge"
            }
        }
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun startScreenTest() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
                record(mapOf("action" to (intent.action ?: "unknown"), "interactive" to interactive))
                latest.text = "${intent.action} interactive=$interactive"
            }
        }
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        record(mapOf("action" to "initial_state", "interactive" to interactive))
        latest.text = "initial interactive=$interactive"
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTest() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            status.text = "Precise Location is required."
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val listener = android.location.LocationListener { location ->
            record(mapOf("provider" to (location.provider ?: "unknown"), "latitude" to location.latitude, "longitude" to location.longitude, "accuracyM" to location.accuracy, "speedMps" to location.speed))
            latest.text = "${location.provider}\n${location.latitude}, ${location.longitude}\n±${location.accuracy}m"
        }
        locationListener = listener
        runCatching { manager.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 5_000L, 0f, listener) }
        runCatching { manager.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 5_000L, 0f, listener) }
    }

    private fun stopTest(reason: String) {
        sensorManager.unregisterListener(this)
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        locationListener?.let { (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).removeUpdates(it) }
        locationListener = null
        if (recorder.isActive()) {
            recorder.stop(reason)
            if (::status.isInitialized) status.text = "Stopped: $reason. Session saved locally."
        }
        activeSource = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val source = activeSource ?: return
        val values = event.values.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.4f", it) }
        val gapMs = if (previousTimestamp == 0L) 0 else (event.timestamp - previousTimestamp) / 1_000_000
        previousTimestamp = event.timestamp
        record(mapOf("sensorTimestampNs" to event.timestamp, "accuracy" to event.accuracy, "values" to values, "gapMs" to gapMs))
        latest.text = "event=$eventCount\nvalues=$values\naccuracy=${event.accuracy} gap=${gapMs}ms"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun record(values: Map<String, Any>) {
        eventCount += 1
        recorder.append(values)
    }

    private fun queryUsageStats() {
        if (!hasUsageAccess()) {
            status.text = "Usage Access disabled. Open Settings and grant access."
            return
        }
        val manager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 86_400_000, end).orEmpty()
        val top = stats.sortedByDescending { it.totalTimeInForeground }.take(5)
        latest.text = if (top.isEmpty()) "No usage rows returned." else top.joinToString("\n") {
            "${it.packageName}: ${it.totalTimeInForeground / 1000}s"
        }
        recorder.start(Source.USAGE_STATS, Build.MANUFACTURER, Build.MODEL)
        top.forEach { recorder.append(mapOf("package" to it.packageName, "foregroundMs" to it.totalTimeInForeground, "lastUsed" to it.lastTimeUsed)) }
        recorder.stop("usage_query_complete")
        status.text = "Saved ${top.size} aggregated usage rows."
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun scheduleUsageSnapshot() {
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<UsageSnapshotWorker>().build())
        status.text = "Usage snapshot scheduled. WorkManager timing is inexact."
    }

    private fun exportNewestSession(format: ExportFormat) {
        val newest = recorder.sessionFiles().firstOrNull()
        if (newest == null) status.text = "No session exists to export." else export(newest, format)
    }

    private fun export(file: File, format: ExportFormat) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = if (format == ExportFormat.JSONL) "application/x-ndjson" else "text/csv"
            putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension + if (format == ExportFormat.JSONL) ".jsonl" else ".csv")
        }
        pendingExport = file
        pendingExportFormat = format
        exportLauncher.launch(intent)
        status.text = "Choose destination to export ${file.name}."
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private var locationListener: android.location.LocationListener? = null

    private fun renderSession(file: File) {
        content = rootLayout()
        header.text = "Saved session"
        content.addView(button("Back to dashboard", false) { renderDashboard() })
        content.addView(title(file.nameWithoutExtension))
        content.addView(mono(recorder.summary(file)))
        content.addView(button("Export JSONL", true) { export(file, ExportFormat.JSONL) })
        content.addView(button("Export CSV", false) { export(file, ExportFormat.CSV) })
        content.addView(button("Delete this session", false) { recorder.delete(file); renderDashboard() })
        setContentView(content.tag as View)
    }

    private enum class ExportFormat { JSONL, CSV }

    data class Source(
        val id: String,
        val title: String,
        val description: String,
        val api: String,
        val unit: String,
        val collectionMode: String,
        val status: String,
        val limit: String,
        val sensorType: Int? = null,
        val permission: String? = null,
        val permissionLabel: String = "permission",
        val backgroundTestSupported: Boolean = false
    ) {
        companion object {
            val USAGE_STATS = Source("usage", "Application usage", "Aggregated app foreground time from Android UsageStats.", "UsageStatsManager.queryUsageStats", "milliseconds", "on-demand query", "Accessible with restrictions", "Requires user-granted Usage Access. Data is aggregated and may be unavailable while device is locked.")
            val CALL_LOG = Source("call-log", "Phone call logs", "Call metadata candidate. Restricted by Android and Google Play policy.", "CallLog.Calls", "metadata", "not implemented", "Not accessible", "READ_CALL_LOG is hard-restricted and a normal sensor-testing PoC is not eligible for default dialer access.")
            val ACCELEROMETER = Source("accelerometer", "Accelerometer", "Three-axis acceleration including gravity.", "Sensor.TYPE_ACCELEROMETER", "m/s²", "event stream", "Accessible with restrictions", "Android limits background sensor delivery. High rates need HIGH_SAMPLING_RATE_SENSORS.", Sensor.TYPE_ACCELEROMETER, backgroundTestSupported = true)
            val GYROSCOPE = Source("gyroscope", "Gyroscope", "Three-axis angular velocity.", "Sensor.TYPE_GYROSCOPE", "rad/s", "event stream", "Device-dependent", "Hardware is not guaranteed. Background delivery is restricted.", Sensor.TYPE_GYROSCOPE, backgroundTestSupported = true)
            val LINEAR = Source("linear", "Linear acceleration", "Three-axis acceleration excluding gravity.", "Sensor.TYPE_LINEAR_ACCELERATION", "m/s²", "event stream", "Device-dependent", "Sensor can be virtual and filtering differs by device.", Sensor.TYPE_LINEAR_ACCELERATION, backgroundTestSupported = true)
            val ROTATION = Source("rotation", "Rotation vector", "Fused orientation vector.", "Sensor.TYPE_ROTATION_VECTOR", "vector components", "event stream", "Device-dependent", "Sensor fusion and magnetic effects differ across devices.", Sensor.TYPE_ROTATION_VECTOR, backgroundTestSupported = true)
            val STEP_COUNTER = Source("step-counter", "Step counter", "Cumulative steps since reboot while sensor is active.", "Sensor.TYPE_STEP_COUNTER", "steps", "event stream", "Device-dependent", "Counter can be delayed and resets after reboot.", Sensor.TYPE_STEP_COUNTER, Manifest.permission.ACTIVITY_RECOGNITION, "Activity Recognition", true)
            val STEP_DETECTOR = Source("step-detector", "Step detector", "One event for each detected step.", "Sensor.TYPE_STEP_DETECTOR", "step event", "event stream", "Device-dependent", "Hardware support and false or missed steps differ by device.", Sensor.TYPE_STEP_DETECTOR, Manifest.permission.ACTIVITY_RECOGNITION, "Activity Recognition", true)
            val SCREEN = Source("screen", "Screen and unlock", "Screen on/off, interactive state, and user-present events while Activity is alive.", "PowerManager and BroadcastReceiver", "boolean/event", "dynamic receiver", "Accessible with restrictions", "Dynamic receivers stop when process dies. Android can defer background broadcasts.")
            val BATTERY = Source("battery", "Battery and charging", "Battery level, scale, and status from sticky battery broadcast.", "BatteryManager", "percent/status", "dynamic receiver", "Accessible", "Battery data is event-driven and not high-frequency continuous telemetry.")
            val LIGHT = Source("light", "Ambient light", "Illuminance near display sensor.", "Sensor.TYPE_LIGHT", "lux", "event stream", "Device-dependent", "Hardware may be absent and readings can change when screen turns off.", Sensor.TYPE_LIGHT, backgroundTestSupported = true)
            val PROXIMITY = Source("proximity", "Proximity", "Near/far distance near earpiece sensor.", "Sensor.TYPE_PROXIMITY", "cm", "event stream", "Device-dependent", "Many devices report only near/far values rather than distance.", Sensor.TYPE_PROXIMITY, backgroundTestSupported = true)
            val LOCATION = Source("location", "Location", "GPS or network location for optional feasibility testing.", "LocationManager", "latitude, longitude, meters", "event stream", "Accessible with restrictions", "Requires precise location. Continuous background collection requires location foreground service and policy justification.", permission = Manifest.permission.ACCESS_FINE_LOCATION, permissionLabel = "Precise Location", backgroundTestSupported = true)

            fun fromId(id: String?): Source? = all.firstOrNull { it.id == id }
            val all = listOf(USAGE_STATS, CALL_LOG, ACCELEROMETER, GYROSCOPE, LINEAR, ROTATION, STEP_COUNTER, STEP_DETECTOR, SCREEN, BATTERY, LIGHT, PROXIMITY, LOCATION)
        }
    }

    private val sources = Source.all
}

class SessionRecorder(private val baseDir: File) {
    private var file: File? = null
    private var sourceTitle = ""

    fun start(source: MainActivity.Source, manufacturer: String, model: String, collectionMode: String = "foreground_activity") {
        stop("replaced")
        baseDir.mkdirs()
        sourceTitle = source.title
        val safeName = source.title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        file = File(baseDir, "${safeName}-${System.currentTimeMillis()}-${UUID.randomUUID()}.jsonl")
        append(mapOf("recordType" to "session_start", "source" to source.title, "collectionMode" to collectionMode, "deviceManufacturer" to manufacturer, "deviceModel" to model, "apiLevel" to Build.VERSION.SDK_INT))
    }

    fun append(values: Map<String, Any>) {
        val destination = file ?: return
        val fields = linkedMapOf<String, Any>(
            "receivedAtEpochMs" to System.currentTimeMillis(),
            "receivedAtIso" to DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        )
        fields.putAll(values)
        destination.appendText(fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"${escape(key)}\":${valueToJson(value)}" } + "\n")
    }

    fun stop(reason: String) {
        if (file != null) append(mapOf("recordType" to "session_end", "source" to sourceTitle, "reason" to reason))
        file = null
        sourceTitle = ""
    }

    fun isActive() = file != null

    fun sessionFiles(): List<File> = baseDir.listFiles { candidate -> candidate.extension == "jsonl" }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    fun delete(file: File) = file.delete()

    fun deleteAll() = sessionFiles().forEach { it.delete() }

    fun summary(file: File): String {
        val rows = file.readLines()
        val eventCount = rows.count { !it.contains("\"recordType\":\"session_") }
        val gaps = Regex("\"gapMs\":(\\d+)").findAll(rows.joinToString()).map { it.groupValues[1].toLong() }.filter { it > 1_500L }.count()
        return "FILE ${file.name}\nROWS ${rows.size}\nEVENTS $eventCount\nGAPS >1500ms $gaps\nSIZE ${file.length()} bytes"
    }

    fun toCsv(file: File): String {
        val rows = file.readLines().map { parseFlatJson(it) }
        val headers = rows.flatMap { it.keys }.distinct()
        return buildString {
            appendLine(headers.joinToString(",") { csv(it) })
            rows.forEach { row -> appendLine(headers.joinToString(",") { csv(row[it].orEmpty()) }) }
        }
    }

    private fun parseFlatJson(line: String): Map<String, String> = Regex("\\\"([^\\\"]+)\\\":(\\\"(?:\\\\.|[^\\\"])*\\\"|[^,}]+)")
        .findAll(line)
        .associate { it.groupValues[1] to it.groupValues[2].trim().removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"").replace("\\n", "\n") }

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""

    private fun valueToJson(value: Any): String = when (value) {
        is Number, is Boolean -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
