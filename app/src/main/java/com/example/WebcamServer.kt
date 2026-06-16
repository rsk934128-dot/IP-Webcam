package com.example

import android.os.BatteryManager
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class WebcamServer(
    private val port: Int,
    private val onToggleFlash: () -> Unit,
    private val onSwitchCamera: () -> Unit,
    private val getAppStats: () -> AppStats,
    private val onUpdateConfig: (String, Int) -> Unit,
    private val getAppConfig: () -> AppConfig
) {
    private val TAG = "WebcamServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    
    // Manage streaming clients thread-safely
    private val clients = ConcurrentHashMap.newKeySet<StreamingClient>()
    
    // Atomic stats
    private val activeViewerCount = AtomicInteger(0)
    
    // Listeners for logging client connectivity changes
    var onLogMessage: ((String) -> Unit)? = null

    data class AppStats(
        val batteryLevel: Int,
        val fps: Int,
        val cameraType: String
    )

    data class AppConfig(
        val resolution: String,
        val targetFps: Int
    )

    class StreamingClient(val socket: Socket, val outputStream: OutputStream) {
        val ipAddress: String = socket.inetAddress?.hostAddress ?: "Unknown"
        
        fun writeFrame(jpegBytes: ByteArray) {
            val header = "--frame\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${jpegBytes.size}\r\n\r\n"
            outputStream.write(header.toByteArray())
            outputStream.write(jpegBytes)
            outputStream.write("\r\n".toByteArray())
            outputStream.flush()
        }

        fun close() {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                log("Server running on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleClientConnection(socket) }
                }
            } catch (e: Exception) {
                log("Server error or stopped: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        
        synchronized(clients) {
            clients.forEach { it.close() }
            clients.clear()
        }
        activeViewerCount.set(0)
        executor.shutdownNow()
        log("Server stopped successfully")
    }

    fun pushFrame(jpegBytes: ByteArray) {
        val iterator = clients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()
            try {
                client.writeFrame(jpegBytes)
            } catch (e: IOException) {
                log("Viewer disconnected: ${client.ipAddress}")
                client.close()
                iterator.remove()
                activeViewerCount.set(clients.size)
            }
        }
    }

    fun getActiveViewerCount(): Int = activeViewerCount.get()

    private fun handleClientConnection(socket: Socket) {
        val clientIp = socket.inetAddress?.hostAddress ?: "Unknown"
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            
            val method = parts[0]
            val path = parts[1]

            // Clear remaining header lines to flush input stream block-free
            var headerLine: String?
            while (reader.readLine().also { headerLine = it } != null) {
                if (headerLine!!.isEmpty()) break
            }

            if (method != "GET") {
                sendResponse(socket.getOutputStream(), "HTTP/1.1 405 Method Not Allowed\r\n\r\nMethod Not Allowed", "text/plain")
                socket.close()
                return
            }

            when {
                path == "/" || path == "/index.html" -> {
                    log("Viewer entry connected: $clientIp")
                    sendResponse(socket.getOutputStream(), HTML_PAGE, "text/html")
                    socket.close()
                }
                path == "/live" || path == "/video" -> {
                    log("Viewer live stream requested: $clientIp")
                    val outputStream = socket.getOutputStream()
                    val headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                            "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Expires: 0\r\n\r\n"
                    outputStream.write(headers.toByteArray())
                    outputStream.flush()

                    val client = StreamingClient(socket, outputStream)
                    clients.add(client)
                    activeViewerCount.set(clients.size)
                    // Keep socket open, it's handoff to broadcasting process!
                }
                path == "/api/stats" -> {
                    val stats = getAppStats()
                    val jsonResponse = "{\n" +
                            "  \"viewerCount\": ${activeViewerCount.get()},\n" +
                            "  \"fps\": ${stats.fps},\n" +
                            "  \"batteryLevel\": ${stats.batteryLevel},\n" +
                            "  \"cameraType\": \"${stats.cameraType}\"\n" +
                            "}"
                    sendResponse(socket.getOutputStream(), jsonResponse, "application/json")
                    socket.close()
                }
                path.startsWith("/api/config") -> {
                    var parsedResolution = ""
                    var parsedFps = -1
                    val uriParts = path.split("?")
                    if (uriParts.size > 1) {
                        val query = uriParts[1]
                        val pairs = query.split("&")
                        for (pair in pairs) {
                            val keyValue = pair.split("=")
                            if (keyValue.size == 2) {
                                val key = keyValue[0]
                                val value = keyValue[1]
                                if (key == "resolution") {
                                    parsedResolution = value
                                } else if (key == "fps") {
                                    parsedFps = value.toIntOrNull() ?: -1
                                }
                            }
                        }
                    }
                    if (parsedResolution.isNotEmpty() || parsedFps != -1) {
                        onUpdateConfig(parsedResolution, parsedFps)
                    }
                    val config = getAppConfig()
                    val jsonResponse = "{\n" +
                            "  \"success\": true,\n" +
                            "  \"resolution\": \"${config.resolution}\",\n" +
                            "  \"targetFps\": ${config.targetFps}\n" +
                            "}"
                    sendResponse(socket.getOutputStream(), jsonResponse, "application/json")
                    socket.close()
                }
                path == "/api/toggle-flash" -> {
                    onToggleFlash()
                    sendResponse(socket.getOutputStream(), "{ \"success\": true }", "application/json")
                    socket.close()
                }
                path == "/api/switch-camera" -> {
                    onSwitchCamera()
                    sendResponse(socket.getOutputStream(), "{ \"success\": true }", "application/json")
                    socket.close()
                }
                else -> {
                    sendResponse(socket.getOutputStream(), "HTTP/1.1 404 Not Found\r\n\r\nFile Not Found", "text/plain")
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: $clientIp", e)
            try { socket.close() } catch (ex: Exception) {}
        }
    }

    private fun sendResponse(outputStream: OutputStream, content: String, contentType: String) {
        val payload = content.toByteArray()
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
        outputStream.write(headers.toByteArray())
        outputStream.write(payload)
        outputStream.flush()
    }

    private fun log(message: String) {
        onLogMessage?.invoke(message)
        Log.d(TAG, message)
    }

    companion object {
        private val HTML_PAGE = """
            <!DOCTYPE html>
            <html lang="en" class="dark">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Sovereign IP Webcam Console</title>
                <script src="https://cdn.tailwindcss.com"></script>
                <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
                <style>
                    body { background-color: #0b0f17; color: #c9d1d9; }
                </style>
            </head>
            <body class="min-h-screen flex flex-col antialiased">
                <header class="border-b border-gray-800 bg-gray-950/80 backdrop-blur-md p-4 sticky top-0 z-50">
                    <div class="max-w-6xl mx-auto flex justify-between items-center">
                        <div class="flex items-center space-x-3">
                            <div class="bg-teal-500 text-black px-2.5 py-1 rounded-md font-mono font-bold text-xs tracking-wider animate-pulse">
                                LIVE
                            </div>
                            <h1 class="text-lg font-extrabold tracking-tight text-white flex items-center">
                                <i class="fa-solid fa-camera-retro mr-2 text-teal-400"></i> Sovereign IP Webcam
                            </h1>
                        </div>
                        <div class="text-xs font-mono text-gray-400" id="uptime">Uptime: 00:00:00</div>
                    </div>
                </header>

                <main class="flex-grow max-w-6xl w-full mx-auto p-4 grid grid-cols-1 md:grid-cols-3 gap-6">
                    <div class="md:col-span-2 space-y-4">
                        <div class="relative bg-black rounded-2xl border border-gray-800 overflow-hidden shadow-2xl aspect-video flex items-center justify-center">
                            <img id="stream" src="/live" class="w-full h-full object-contain" alt="Live stream feed">
                            <div class="absolute top-4 left-4 bg-black/60 backdrop-blur-md text-teal-400 px-3 py-1 rounded-full text-xs font-mono font-bold flex items-center space-x-2">
                                <span class="w-2 h-2 rounded-full bg-teal-500 animate-ping"></span>
                                <span>HD Live Feed</span>
                            </div>
                        </div>
                        <div class="bg-gray-900/40 border border-gray-800/80 rounded-xl p-4 text-xs text-gray-400 flex items-start space-x-3">
                            <i class="fa-solid fa-circle-info text-teal-500 mt-0.5"></i>
                            <p>This stream is processed in real-time. Enter <code>/live</code> or <code>/video</code> directly in software like OBS, VLC, or home assistant to pull this live stream feed.</p>
                        </div>
                    </div>

                    <div class="space-y-6">
                        <div class="bg-gray-900 border border-gray-800 rounded-2xl p-6 shadow-xl space-y-4">
                            <h3 class="text-xs font-semibold tracking-wider uppercase text-gray-400">Device Controls</h3>
                            <div class="grid grid-cols-2 gap-4">
                                <button onclick="toggleAction('/api/toggle-flash')" class="flex flex-col items-center justify-center p-4 bg-gray-800/30 hover:bg-teal-500/10 hover:border-teal-500/50 border border-gray-800 transition-all duration-300 rounded-xl group text-teal-400">
                                    <i class="fa-solid fa-lightbulb text-2xl mb-2 group-hover:scale-110 transition-transform"></i>
                                    <span class="text-xs font-medium text-gray-300">Flashlight</span>
                                </button>
                                <button onclick="toggleAction('/api/switch-camera')" class="flex flex-col items-center justify-center p-4 bg-gray-800/30 hover:bg-pink-500/10 hover:border-pink-500/50 border border-gray-800 transition-all duration-300 rounded-xl group text-pink-400">
                                    <i class="fa-solid fa-camera-rotate text-2xl mb-2 group-hover:scale-110 transition-transform"></i>
                                    <span class="text-xs font-medium text-gray-300">Flip Camera</span>
                                </button>
                            </div>
                        </div>

                        <div class="bg-gray-900 border border-gray-800 rounded-2xl p-6 shadow-xl space-y-4">
                            <h3 class="text-xs font-semibold tracking-wider uppercase text-gray-400">Live Diagnostics</h3>
                            <div class="grid grid-cols-2 gap-4">
                                <div class="p-4 bg-gray-800/20 rounded-xl border border-gray-800/40">
                                    <span class="block text-[10px] text-gray-400 mb-1">Viewer Count</span>
                                    <div class="flex items-baseline space-x-1.5">
                                        <span class="text-2xl font-bold text-white font-mono" id="viewer-count">0</span>
                                        <span class="text-[9px] text-teal-400 uppercase">online</span>
                                    </div>
                                </div>
                                <div class="p-4 bg-gray-800/20 rounded-xl border border-gray-800/40">
                                    <span class="block text-[10px] text-gray-400 mb-1">FPS Estimate</span>
                                    <div class="flex items-baseline space-x-1.5">
                                        <span class="text-2xl font-bold text-white font-mono" id="fps">0</span>
                                        <span class="text-[9px] text-gray-500">FPS</span>
                                    </div>
                                </div>
                                <div class="p-4 bg-gray-800/20 rounded-xl border border-gray-800/40">
                                    <span class="block text-[10px] text-gray-400 mb-1">Battery Level</span>
                                    <div class="flex items-baseline space-x-1.5">
                                        <span class="text-2xl font-bold text-white font-mono" id="battery">--%</span>
                                        <i id="battery-icon" class="fa-solid fa-battery-three-quarters text-sm text-green-400"></i>
                                    </div>
                                </div>
                                <div class="p-4 bg-gray-800/20 rounded-xl border border-gray-800/40">
                                    <span class="block text-[10px] text-gray-400 mb-1">Camera Mode</span>
                                    <div class="flex items-baseline space-x-1.5">
                                        <span class="text-sm font-black text-white tracking-wider uppercase font-mono" id="camera-mode">BACK</span>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </main>

                <footer class="bg-gray-950 border-t border-gray-900 p-4 text-center text-xs text-gray-500 font-mono">
                    &copy; 2026 Sovereign IP Webcam. Powered by High-Performance Multithreaded Kotlin Socket Engine.
                </footer>

                <script>
                    let startTime = Date.now();

                    function updateUptime() {
                        let elapsedSecs = Math.floor((Date.now() - startTime) / 1000);
                        let hrs = String(Math.floor(elapsedSecs / 3600)).padStart(2, '0');
                        let mins = String(Math.floor((elapsedSecs % 3600) / 60)).padStart(2, '0');
                        let secs = String(elapsedSecs % 60).padStart(2, '0');
                        document.getElementById('uptime').innerText = "Uptime: " + hrs + ":" + mins + ":" + secs;
                    }
                    setInterval(updateUptime, 1000);

                    async function fetchStats() {
                        try {
                            let response = await fetch('/api/stats');
                            let data = await response.json();
                            document.getElementById('viewer-count').innerText = data.viewerCount;
                            document.getElementById('fps').innerText = data.fps;
                            document.getElementById('battery').innerText = data.batteryLevel + '%';
                            document.getElementById('camera-mode').innerText = data.cameraType;
                            
                            let bIcon = document.getElementById('battery-icon');
                            if(data.batteryLevel > 75) {
                                bIcon.className = "fa-solid fa-battery-full text-green-500";
                            } else if(data.batteryLevel > 35) {
                                bIcon.className = "fa-solid fa-battery-half text-yellow-500";
                            } else {
                                bIcon.className = "fa-solid fa-battery-empty text-red-500 animate-pulse";
                            }
                        } catch(err) {
                            console.error("Failed to pull specs status", err);
                        }
                    }
                    setInterval(fetchStats, 2000);
                    fetchStats();

                    async function toggleAction(endpoint) {
                        try {
                            let r = await fetch(endpoint);
                            let res = await r.json();
                            if(res.success) {
                                setTimeout(fetchStats, 400);
                            }
                        } catch(e) {
                            console.error("Action transmission failure", e);
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
