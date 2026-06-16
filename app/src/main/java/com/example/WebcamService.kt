package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.app.Service
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class WebcamService : Service(), LifecycleOwner {
    private val TAG = "WebcamService"

    private lateinit var lifecycleRegistry: LifecycleRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private var server: WebcamServer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    private var lastFpsTimestamp = 0L
    private var frameCount = 0
    private var lastPushedTimestamp = 0L

    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var isCloudPushing = false

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val KEY_PORT = "KEY_PORT"

        val isRunning = MutableStateFlow(false)
        val serverUrl = MutableStateFlow("")
        val activeViewers = MutableStateFlow(0)
        val streamFps = MutableStateFlow(0)
        val cameraType = MutableStateFlow("BACK") // "BACK" or "FRONT"
        val flashLightOn = MutableStateFlow(false)
        val logs = MutableStateFlow<List<String>>(emptyList())
        val batteryPct = MutableStateFlow(100)
        val selectedPort = MutableStateFlow(8080)
        val localSurfaceProvider = MutableStateFlow<androidx.camera.core.Preview.SurfaceProvider?>(null)
        val selectedResolution = MutableStateFlow("Medium") // "High", "Medium", "Low"
        val targetFps = MutableStateFlow(15) // limit: 5, 10, 15, 24, 30
        
        val cloudServerUrl = MutableStateFlow("")
        val isCloudPushEnabled = MutableStateFlow(false)

        private val logList = mutableListOf<String>()

        fun addLog(msg: String) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
            val formattedMsg = "[$timestamp] $msg"
            logList.add(0, formattedMsg)
            if (logList.size > 100) {
                logList.removeAt(logList.size - 1)
            }
            logs.value = logList.toList()
        }

        fun getLocalIpAddress(context: Context): String {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val ipAddress = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipAddress != 0) {
                return String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
            }
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val element = interfaces.nextElement()
                    val addresses = element.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: ""
                            if (host.isNotEmpty()) return host
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            return "127.0.0.1"
        }
    }

    override fun onCreate() {
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        updateBatteryStatus()
        
        // Initialize cloud variables from persistent SharedPreferences
        val prefs = getSharedPreferences("WebcamPrefs", Context.MODE_PRIVATE)
        cloudServerUrl.value = prefs.getString("cloud_url", "") ?: ""
        isCloudPushEnabled.value = prefs.getBoolean("cloud_push", false)
        
        lifecycleScope.launch {
            localSurfaceProvider.collect {
                if (isRunning.value) {
                    bindCameraUseCases()
                }
            }
        }
        
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(KEY_PORT, 8080)
                selectedPort.value = port
                startWebcam(port)
            }
            ACTION_STOP -> {
                stopWebcam()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun startWebcam(port: Int) {
        if (isRunning.value) return
        isRunning.value = true
        addLog("Starting IP Webcam stream...")

        val ip = getLocalIpAddress(this)
        serverUrl.value = "$ip:$port"

        // Initialize embedded socket Web-Server
        server = WebcamServer(
            port = port,
            onToggleFlash = { toggleFlashlight() },
            onSwitchCamera = { switchCamera() },
            getAppStats = {
                updateBatteryStatus()
                WebcamServer.AppStats(
                    batteryLevel = batteryPct.value,
                    fps = streamFps.value,
                    cameraType = cameraType.value
                )
            },
            onUpdateConfig = { res, fps ->
                if (res.isNotEmpty() && res != selectedResolution.value) {
                    selectedResolution.value = res
                    addLog("Resolution set via API: $res")
                    ContextCompat.getMainExecutor(this).execute {
                        bindCameraUseCases()
                    }
                }
                if (fps != -1 && fps != targetFps.value) {
                    targetFps.value = fps
                    addLog("Frame-rate set via API: $fps FPS")
                }
            },
            getAppConfig = {
                WebcamServer.AppConfig(
                    resolution = selectedResolution.value,
                    targetFps = targetFps.value
                )
            }
        ).apply {
            onLogMessage = { msg ->
                addLog(msg)
                // Monitor viewer counts
                activeViewers.value = getActiveViewerCount()
            }
            start()
        }

        startForegroundNotification()
        lifecycleScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(800)
            setupCamera()
        }
        addLog("Server loaded. Stream live at http://${serverUrl.value}")
    }

    private fun stopWebcam() {
        if (!isRunning.value) return
        isRunning.value = false
        addLog("Shutting down IP Webcam system...")
        
        server?.stop()
        server = null
        
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            // Ignore
        }
        
        flashLightOn.value = false
        streamFps.value = 0
        activeViewers.value = 0
        
        stopForeground(true)
        stopSelf()
        addLog("Sovereign IP Webcam is offline.")
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                addLog("Camera Setup Error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        cameraProvider.unbindAll()

        val cameraSelector = if (cameraType.value == "BACK") {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val useCases = mutableListOf<androidx.camera.core.UseCase>()

        val resolutionSize = when (selectedResolution.value) {
            "High" -> android.util.Size(1280, 720)
            "Low" -> android.util.Size(320, 240)
            else -> android.util.Size(640, 480) // Medium
        }

        @Suppress("DEPRECATION")
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetResolution(resolutionSize)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                val now = System.currentTimeMillis()
                val minInterval = 1000L / targetFps.value
                if (now - lastPushedTimestamp >= minInterval) {
                    lastPushedTimestamp = now
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val jpegBytes = imageProxy.toJpeg(75, rotationDegrees)
                    server?.pushFrame(jpegBytes)
                    
                    if (isCloudPushEnabled.value && cloudServerUrl.value.isNotEmpty()) {
                        pushFrameToCloud(jpegBytes)
                    }
                    
                    calculateFps()
                }
            } catch (e: Exception) {
                // Ignore
            } finally {
                imageProxy.close()
            }
        }
        useCases.add(imageAnalysis)

        val localSP = localSurfaceProvider.value
        if (localSP != null) {
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.surfaceProvider = localSP
            }
            useCases.add(preview)
        }

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, *useCases.toTypedArray())
            // Restore flashlight state if applicable
            camera?.cameraControl?.enableTorch(flashLightOn.value)
        } catch (e: Exception) {
            addLog("Camera binding failed: ${e.message}")
        }
    }

    private fun pushFrameToCloud(jpegBytes: ByteArray) {
        val url = cloudServerUrl.value.trim().replace(Regex("/+$"), "")
        if (url.isEmpty()) return
        
        if (isCloudPushing) return
        isCloudPushing = true

        val requestUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            "$url/api/stream/push"
        } else {
            "http://$url/api/stream/push"
        }

        val mediaType = "image/jpeg".toMediaTypeOrNull()
        val requestBody = jpegBytes.toRequestBody(mediaType)
        
        updateBatteryStatus()

        val request = okhttp3.Request.Builder()
            .url(requestUrl)
            .post(requestBody)
            .addHeader("X-Battery-Level", batteryPct.value.toString())
            .addHeader("X-FPS", streamFps.value.toString())
            .addHeader("X-Camera-Type", cameraType.value)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                isCloudPushing = false
                Log.e(TAG, "Cloud push failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                isCloudPushing = false
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    handleCloudResponse(bodyString)
                } else {
                    Log.e(TAG, "Cloud push returned code: ${response.code}")
                }
                response.close()
            }
        })
    }

    private fun handleCloudResponse(responseBody: String) {
        try {
            val json = org.json.JSONObject(responseBody)
            if (json.has("commands")) {
                val commandsArray = json.getJSONArray("commands")
                for (i in 0 until commandsArray.length()) {
                    val cmd = commandsArray.getString(i)
                    handleCloudCommand(cmd)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cloud commands: ${e.message}")
        }
    }

    private fun handleCloudCommand(cmd: String) {
        ContextCompat.getMainExecutor(this).execute {
            try {
                when {
                    cmd == "toggle_flash" -> {
                        addLog("Cloud command received: Toggle Flashlight")
                        toggleFlashlight()
                    }
                    cmd == "switch_camera" -> {
                        addLog("Cloud command received: Switch Camera Lens")
                        switchCamera()
                    }
                    cmd.startsWith("set_resolution_") -> {
                        val res = cmd.removePrefix("set_resolution_")
                        if (res in listOf("High", "Medium", "Low")) {
                            addLog("Cloud command received: Set Resolution to $res")
                            selectedResolution.value = res
                            bindCameraUseCases()
                        }
                    }
                    cmd.startsWith("set_fps_") -> {
                        val fps = cmd.removePrefix("set_fps_").toIntOrNull()
                        if (fps != null && fps in listOf(5, 10, 15, 24, 30)) {
                            addLog("Cloud command received: Set Target FPS to $fps")
                            targetFps.value = fps
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing cloud command $cmd: ${e.message}")
            }
        }
    }

    private fun switchCamera() {
        val current = cameraType.value
        val next = if (current == "BACK") "FRONT" else "BACK"
        cameraType.value = next
        flashLightOn.value = false // Flash doesn't support front cameras
        addLog("Switching camera to $next")
        
        // Push Camera updates back to the UI thread to re-bind
        ContextCompat.getMainExecutor(this).execute {
            bindCameraUseCases()
        }
    }

    private fun toggleFlashlight() {
        if (cameraType.value == "FRONT") {
            addLog("Flashlight is unavailable on front-facing lens")
            return
        }
        val isTorch = !flashLightOn.value
        flashLightOn.value = isTorch
        addLog("Toggling device flashlight: ${if (isTorch) "ON" else "OFF"}")
        
        ContextCompat.getMainExecutor(this).execute {
            camera?.cameraControl?.enableTorch(isTorch)
        }
    }

    private fun calculateFps() {
        frameCount++
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFpsTimestamp >= 1000) {
            val calculatedFps = (frameCount * 1000 / (currentTime - lastFpsTimestamp)).toInt()
            streamFps.value = calculatedFps
            frameCount = 0
            lastFpsTimestamp = currentTime
            
            // Periodically check battery status
            updateBatteryStatus()
        }
    }

    private fun updateBatteryStatus() {
        val batteryStatus: Intent? = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            this.registerReceiver(null, filter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            batteryPct.value = (level * 100 / scale.toFloat()).toInt()
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "webcam_channel",
                "IP Webcam Service Channel",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification shown when live camera stream is hosting"
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "webcam_channel")
            .setContentTitle("Sovereign IP Webcam Running")
            .setContentText("Stream url: http://${serverUrl.value}")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(2652, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(2652, notification)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopWebcam()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    // Helper conversion YUV 420 888 image frames -> Jpegs
    private fun ImageProxy.toJpeg(quality: Int, rotation: Int): ByteArray {
        val planeY = planes[0]
        val planeU = planes[1]
        val planeV = planes[2]

        val bufferY = planeY.buffer
        val bufferU = planeU.buffer
        val bufferV = planeV.buffer

        bufferY.rewind()
        bufferU.rewind()
        bufferV.rewind()

        val ySize = bufferY.remaining()
        val uSize = bufferU.remaining()
        val vSize = bufferV.remaining()

        val nv21 = ByteArray(width * height * 3 / 2)

        val rowStrideY = planeY.rowStride
        val pixelStrideY = planeY.pixelStride
        val rowStrideU = planeU.rowStride
        val pixelStrideU = planeU.pixelStride
        val rowStrideV = planeV.rowStride
        val pixelStrideV = planeV.pixelStride

        var pos = 0
        if (pixelStrideY == 1 && rowStrideY == width) {
            bufferY.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                bufferY.position(row * rowStrideY)
                for (col in 0 until width) {
                    nv21[pos++] = bufferY.get()
                }
            }
        }

        val uvWidth = width / 2
        val uvHeight = height / 2

        for (row in 0 until uvHeight) {
            val rowOffsetU = row * rowStrideU
            val rowOffsetV = row * rowStrideV
            for (col in 0 until uvWidth) {
                val offsetU = rowOffsetU + col * pixelStrideU
                val offsetV = rowOffsetV + col * pixelStrideV

                nv21[pos++] = bufferV.get(offsetV)
                nv21[pos++] = bufferU.get(offsetU)
            }
        }

        val out = ByteArrayOutputStream()
        val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)

        val jpegBytes = out.toByteArray()
        return if (rotation != 0) {
            rotateJpeg(jpegBytes, rotation)
        } else {
            jpegBytes
        }
    }

    private fun rotateJpeg(jpegBytes: ByteArray, rotationDegrees: Int): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val out = ByteArrayOutputStream()
        rotatedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
        bitmap.recycle()
        rotatedBitmap.recycle()
        return out.toByteArray()
    }
}
