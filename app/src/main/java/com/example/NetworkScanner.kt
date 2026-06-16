package com.example

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

object NetworkScanner {
    private const val TAG = "NetworkScanner"

    data class DiscoveredCamera(
        val ip: String,
        val port: Int,
        val name: String,
        val url: String,
        val isOurApp: Boolean,
        val lastSeen: Long = System.currentTimeMillis()
    )

    val isScanning = MutableStateFlow(false)
    val scanProgress = MutableStateFlow(0f) // 0.0 to 1.0
    val scanProgressText = MutableStateFlow("স্ক্যান শুরু করতে আলতো চাপুন")
    val discoveredCameras = MutableStateFlow<List<DiscoveredCamera>>(emptyList())

    private var scanJob: Job? = null
    
    val COMMON_PORTS = listOf(80, 554, 8080, 8081, 8181)

    fun startScan(context: Context, customPort: Int? = null) {
        if (isScanning.value) return
        isScanning.value = true
        scanProgress.value = 0f
        scanProgressText.value = "স্ক্যানিং শুরু হচ্ছে..."
        discoveredCameras.value = emptyList()

        val scanScope = CoroutineScope(Dispatchers.IO)
        scanJob = scanScope.launch {
            try {
                val localIp = WebcamService.getLocalIpAddress(context)
                if (localIp.isEmpty() || localIp == "127.0.0.1" || localIp == "0.0.0.0") {
                    withContext(Dispatchers.Main) {
                        scanProgressText.value = "ওয়াইফাই নেটওয়ার্কের সাথে সংযুক্ত থাকুন"
                        isScanning.value = false
                    }
                    return@launch
                }

                val lastDot = localIp.lastIndexOf('.')
                if (lastDot == -1) {
                    withContext(Dispatchers.Main) {
                        scanProgressText.value = "আইপি অ্যাড্রেস ফরম্যাট সঠিক নয়"
                        isScanning.value = false
                    }
                    return@launch
                }

                val subnet = localIp.substring(0, lastDot)
                val portsToScan = mutableListOf<Int>()
                if (customPort != null) {
                    portsToScan.add(customPort)
                }
                COMMON_PORTS.forEach {
                    if (it != customPort && !portsToScan.contains(it)) {
                        portsToScan.add(it)
                    }
                }

                val totalHosts = 254
                val totalPortScanCount = totalHosts * portsToScan.size
                var completedScans = 0

                val foundList = mutableListOf<DiscoveredCamera>()
                val semaphore = Semaphore(40) // Limit concurrent connections to be network-friendly
                val deferreds = mutableListOf<Deferred<Unit>>()

                for (host in 1..254) {
                    val targetIp = "$subnet.$host"

                    for (port in portsToScan) {
                        val d = async(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (!isScanning.value) return@withPermit
                                val isOpen = checkPortOpen(targetIp, port, 750)
                                if (isOpen && isScanning.value) {
                                    val isSovereign = verifySovereignPort(targetIp, port)
                                    val name = when {
                                        isSovereign -> "Sovereign IP Camera-সহপাঠী"
                                        port == 554 -> "নিরাপত্তা ক্যামেরা (RTSP)"
                                        port == 8080 || port == 8081 || port == 8181 -> "MJPEG আইপি ক্যামেরা"
                                        port == 80 -> "ওয়েব ক্যামেরা সার্ভিস (HTTP)"
                                        else -> "সক্রিয় ক্যামেরা পোর্ট ($port)"
                                    }
                                    val url = when {
                                        port == 554 -> "rtsp://$targetIp:554/live"
                                        isSovereign -> "http://$targetIp:$port/live"
                                        else -> "http://$targetIp:$port"
                                    }

                                    synchronized(foundList) {
                                        foundList.add(
                                            DiscoveredCamera(
                                                ip = targetIp,
                                                port = port,
                                                name = name,
                                                url = url,
                                                isOurApp = isSovereign
                                            )
                                        )
                                        discoveredCameras.value = foundList.toList()
                                    }
                                }
                                synchronized(this@NetworkScanner) {
                                    completedScans++
                                    val progress = completedScans.toFloat() / totalPortScanCount
                                    scanProgress.value = progress
                                    scanProgressText.value = "স্ক্যানিং: $targetIp (${(progress * 100).toInt()}%)"
                                }
                            }
                        }
                        deferreds.add(d)
                    }
                }

                deferreds.awaitAll()

                withContext(Dispatchers.Main) {
                    scanProgressText.value = "স্ক্যান সম্পন্ন! ${foundList.size}টি ক্যামেরা সচল পাওয়া গেছে।"
                    isScanning.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scan Exception: ", e)
                withContext(Dispatchers.Main) {
                    scanProgressText.value = "ত্রুটি: ${e.localizedMessage}"
                    isScanning.value = false
                }
            }
        }
    }

    fun stopScan() {
        isScanning.value = false
        scanJob?.cancel()
        scanProgressText.value = "স্ক্যান বাতিল করা হয়েছে।"
    }

    private fun checkPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun verifySovereignPort(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.soTimeout = 750
                socket.connect(InetSocketAddress(ip, port), 750)
                socket.getOutputStream().write("GET /api/stats HTTP/1.1\r\nHost: $ip\r\nConnection: close\r\n\r\n".toByteArray())
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val firstLine = reader.readLine()
                var isSovereign = false
                if (firstLine != null && firstLine.contains("200 OK")) {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.contains("viewerCount") || line!!.contains("cameraType")) {
                            isSovereign = true
                            break
                        }
                    }
                }
                isSovereign
            }
        } catch (e: Exception) {
            false
        }
    }
}
