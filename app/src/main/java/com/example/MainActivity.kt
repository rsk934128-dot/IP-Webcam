package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.Purple40
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides Dp.Unspecified
                    ) {
                        AppPermissionWrapper(innerPadding)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AppPermissionWrapper(innerPadding: PaddingValues) {
    val permissions = mutableListOf(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val permissionState = rememberMultiplePermissionsState(permissions = permissions)

    if (permissionState.allPermissionsGranted) {
        WebcamDashboardScreen()
    } else {
        PermissionRequestScreen(
            onRequest = { permissionState.launchMultiplePermissionRequest() }
        )
    }
}

@Composable
fun PermissionRequestScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B19))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(Color(0xFF14243B), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = "Camera Permission Required",
                    tint = Color(0xFF00ADB5),
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "ক্যামেরা পারমিশন আবশ্যক",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Sovereign IP Webcam-এর মাধ্যমে ফোনের ক্যামেরা ভিডিও ফিড লোকাল নেটওয়ার্কে লাইভ দেখার জন্য ক্যামেরা ব্যবহারের পারমিশন প্রয়োজন।",
                color = Color(0xFFA5B4FC),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00ADB5),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("grant_permission_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "পারমিশন অনুমতি দিন",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun WebcamDashboardScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val prefs = remember { context.getSharedPreferences("WebcamPrefs", Context.MODE_PRIVATE) }
    var cloudUrlInput by remember { mutableStateOf(prefs.getString("cloud_url", "") ?: "") }
    var cloudPushEnabledInput by remember { mutableStateOf(prefs.getBoolean("cloud_push", false)) }

    // Sync cloud settings whenever changed
    LaunchedEffect(cloudUrlInput) {
        prefs.edit().putString("cloud_url", cloudUrlInput).apply()
        WebcamService.cloudServerUrl.value = cloudUrlInput
    }

    LaunchedEffect(cloudPushEnabledInput) {
        prefs.edit().putBoolean("cloud_push", cloudPushEnabledInput).apply()
        WebcamService.isCloudPushEnabled.value = cloudPushEnabledInput
    }

    // State bindings from Service
    val isRunning by WebcamService.isRunning.collectAsState()
    val serverUrl by WebcamService.serverUrl.collectAsState()
    val activeViewers by WebcamService.activeViewers.collectAsState()
    val streamFps by WebcamService.streamFps.collectAsState()
    val cameraType by WebcamService.cameraType.collectAsState()
    val flashLightOn by WebcamService.flashLightOn.collectAsState()
    val logs by WebcamService.logs.collectAsState()
    val batteryPct by WebcamService.batteryPct.collectAsState()
    val selectedResolution by WebcamService.selectedResolution.collectAsState()
    val targetFps by WebcamService.targetFps.collectAsState()

    var portInput by remember { mutableStateOf("8080") }

    // Synchronize initial port
    LaunchedEffect(Unit) {
        portInput = WebcamService.selectedPort.value.toString()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B19)),
        containerColor = Color(0xFF070B19),
        contentWindowInsets = WindowInsets.statusBars
    ) { insetsPadding ->
        val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = insetsPadding.calculateTopPadding(), bottom = bottomPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Header Title block
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = "Sovereign IP Webcam Logo",
                        tint = Color(0xFF00ADB5),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Sovereign IP Webcam",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = "Local WebRTC/MJPEG Streamer",
                            color = Color(0xFF6B7280),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Dynamic live badge
                if (isRunning) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    Box(
                        modifier = Modifier
                            .scale(pulseAlpha)
                            .background(Color(0xFF0F766E), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "LIVE",
                            color = Color(0xFF2DD4BF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1F2937), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "OFFLINE",
                            color = Color(0xFF9CA3AF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main stream control layout (Two columns on tablets, single column on standard layout)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // Section 1: Server URL Display card
                item {
                    val bgGradient = if (isRunning) {
                        Brush.linearGradient(listOf(Color(0xFF0E1A2F), Color(0xFF122C42)))
                    } else {
                        Brush.linearGradient(listOf(Color(0xFF0F1524), Color(0xFF0F1524)))
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (isRunning) Color(0xFF00ADB5).copy(alpha = 0.5f) else Color(0xFF1F2538),
                                shape = RoundedCornerShape(16.dp)
                            ),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgGradient)
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.SignalWifi4Bar,
                                            contentDescription = null,
                                            tint = if (isRunning) Color(0xFF00ADB5) else Color(0xFF4B5563),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "লোকাল লিংক ঠিকানা",
                                            color = Color(0xFF9CA3AF),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = if (isRunning) "ব্রাউজারে ভিউ করুন" else "ক্যামেরা বন্ধ রয়েছে",
                                        color = if (isRunning) Color(0xFF2DD4BF) else Color(0xFF6B7280),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                val urlStr = if (isRunning) "http://$serverUrl" else "http://${WebcamService.getLocalIpAddress(context)}:$portInput"
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF070B16), RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = urlStr,
                                        color = if (isRunning) Color.White else Color(0xFF6B7280),
                                        fontSize = 15.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (isRunning) {
                                        Row {
                                            IconButton(
                                                onClick = {
                                                    clipboardManager.setText(AnnotatedString(urlStr))
                                                    Toast.makeText(context, "লিঙ্ক ক্লিপবোর্ডে কপি করা হয়েছে!", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(36.dp).testTag("copy_link_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.ContentCopy,
                                                    contentDescription = "Copy Link to Clipboard",
                                                    tint = Color(0xFF00ADB5),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                            IconButton(
                                                onClick = {
                                                    val sendIntent = Intent().apply {
                                                        action = Intent.ACTION_SEND
                                                        type = "text/plain"
                                                        putExtra(Intent.EXTRA_TEXT, urlStr)
                                                    }
                                                    context.startActivity(Intent.createChooser(sendIntent, "Share URL"))
                                                },
                                                modifier = Modifier.size(36.dp).testTag("share_link_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Share,
                                                    contentDescription = "Share Link",
                                                    tint = Color(0xFF00ADB5),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 2: Port selector and big Power Stream Toggle
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Port configuration box
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { input ->
                                if (!isRunning && input.all { it.isDigit() } && input.length <= 5) {
                                    portInput = input
                                }
                            },
                            enabled = !isRunning,
                            label = { Text("পোর্ট সার্ভিস (Port)", fontSize = 11.sp, color = if(isRunning) Color.Transparent else Color(0xFFA5B4FC)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                disabledTextColor = Color(0xFF6B7280),
                                focusedContainerColor = Color(0xFF0D1224),
                                unfocusedContainerColor = Color(0xFF0D1224),
                                disabledContainerColor = Color(0xFF0B0F1A).copy(alpha = 0.8f),
                                focusedBorderColor = Color(0xFF00ADB5),
                                unfocusedBorderColor = Color(0xFF1F2538),
                                disabledBorderColor = Color(0xFF161B2B)
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(0.4f)
                                .testTag("port_input_field")
                        )

                        // Stream Toggle Action Button (Vibrant power button style)
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                val parsedPort = portInput.toIntOrNull() ?: 8080
                                
                                val intent = Intent(context, WebcamService::class.java).apply {
                                    action = if (isRunning) WebcamService.ACTION_STOP else WebcamService.ACTION_START
                                    putExtra(WebcamService.KEY_PORT, parsedPort)
                                }
                                if (isRunning) {
                                    context.startService(intent)
                                } else {
                                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) Color(0xFFEF4444) else Color(0xFF00ADB5),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(0.6f)
                                .height(56.dp)
                                .testTag("power_toggle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PowerSettingsNew,
                                contentDescription = if (isRunning) "Stop stream" else "Start stream",
                                modifier = Modifier.size(24.dp),
                                tint = if (isRunning) Color.White else Color.Black
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isRunning) "স্ট্রিমিং বন্ধ করুন" else "ক্যামেরা চালু করুন",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isRunning) Color.White else Color.Black
                            )
                        }
                    }
                }

                // Section 2.2: Cloud Streaming Setup (Render / Railway deploy setup)
                item {
                    val context = LocalContext.current
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(width = 1.dp, color = Color(0xFF1F2538), shape = RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1224)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudUpload,
                                    contentDescription = "Cloud Setup Icon",
                                    tint = Color(0xFF3B82F6),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ক্লাউড স্ট্রিমিং সেটআপ (Cloud Stream)",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Render বা Railway-তে হোস্ট করা আপনার ওয়েবসাইটের ইউআরএল দিন। এর মাধ্যমে কোনো পাবলিক আইপি বা পোর্ট ফরওয়ার্ডিং ছাড়াই লাইভ স্ট্রিম চলবে।",
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            
                            // Enable Cloud Push Toggle Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.SwapHoriz,
                                        contentDescription = "Push status marker",
                                        tint = if (cloudPushEnabledInput) Color(0xFF10B981) else Color(0xFF4B5563),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "ক্লাউড পুশ সক্রিয় করুন",
                                        color = if (cloudPushEnabledInput) Color.White else Color(0xFF9CA3AF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Switch(
                                    checked = cloudPushEnabledInput,
                                    onCheckedChange = { isChecked ->
                                        cloudPushEnabledInput = isChecked
                                        WebcamService.addLog("Cloud streaming toggled to: ${if(isChecked) "ACTIVE" else "INACTIVE"}")
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF00ADB5),
                                        uncheckedThumbColor = Color(0xFF9CA3AF),
                                        uncheckedTrackColor = Color(0xFF1F2538)
                                    ),
                                    modifier = Modifier.scale(0.85f)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            // Cloud URL input box
                            OutlinedTextField(
                                value = cloudUrlInput,
                                onValueChange = { cloudUrlInput = it },
                                placeholder = { Text("https://my-app-name.onrender.com", fontSize = 12.sp, color = Color(0xFF4B5563)) },
                                label = { Text("ক্লাউড সার্ভার ইউআরএল (Cloud URL)", fontSize = 11.sp, color = Color(0xFFA5B4FC)) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = Color(0xFF060913),
                                    unfocusedContainerColor = Color(0xFF060913),
                                    focusedBorderColor = Color(0xFF00ADB5),
                                    unfocusedBorderColor = Color(0xFF1F2538)
                                ),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("cloud_url_field")
                            )
                        }
                    }
                }

                // Section 2.3: Subnet Scanner Utility
                item {
                    val context = LocalContext.current
                    val scIsScanning by NetworkScanner.isScanning.collectAsState()
                    val scProgress by NetworkScanner.scanProgress.collectAsState()
                    val scProgressText by NetworkScanner.scanProgressText.collectAsState()
                    val scCameras by NetworkScanner.discoveredCameras.collectAsState()

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(width = 1.dp, color = Color(0xFF1F2538), shape = RoundedCornerShape(16.dp))
                            .testTag("subnet_scanner_card"),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1224)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            // Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.SignalWifi4Bar,
                                        contentDescription = "Scanner Wifi Icon",
                                        tint = Color(0xFF00ADB5),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "লোকাল ক্যামেরা স্ক্যানার (Subnet Scanner)",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                // Pulse dot indicator when scanning
                                if (scIsScanning) {
                                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                    val alpha by infiniteTransition.animateFloat(
                                        initialValue = 0.3f,
                                        targetValue = 1.0f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(800),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "alpha"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFEF4444).copy(alpha = alpha))
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "আপনার ওয়াইফাই সাবনেট প্রোভ করে সংযুক্ত ও সক্রিয় আইপি ক্যামেরা অথবা অন্যান্য Sovereign ডিভাইসগুলো খুঁজুন।",
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            // Control parameters (Scan button & progress)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (scIsScanning) {
                                            NetworkScanner.stopScan()
                                        } else {
                                            val customPortInt = portInput.toIntOrNull()
                                            NetworkScanner.startScan(context, customPortInt)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (scIsScanning) Color(0xFFEF4444) else Color(0xFF00ADB5),
                                        contentColor = if (scIsScanning) Color.White else Color.Black
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .testTag("start_subnet_scan_button"),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Icon(
                                        imageVector = if (scIsScanning) Icons.Filled.Stop else Icons.Filled.Refresh,
                                        contentDescription = if (scIsScanning) "Stop Search" else "Start Search",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (scIsScanning) "স্ক্যান বন্ধ করুন" else "ক্যামেরা অনুসন্ধান করুন",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Scan status & progress bar
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF060913), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0xFF1F2538), RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = scProgressText,
                                    color = if (scIsScanning) Color(0xFF00ADB5) else Color(0xFF9CA3AF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (scIsScanning) {
                                    LinearProgressIndicator(
                                        progress = scProgress,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = Color(0xFF00ADB5),
                                        trackColor = Color(0xFF1F2538)
                                    )
                                }
                            }

                            // Discovered Cam List Card Segment
                            if (scCameras.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "আবিষ্কৃত ক্যামেরা সমূহ (${scCameras.size} টি পাওয়া গেছে):",
                                    color = Color(0xFFA5B4FC),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    scCameras.forEach { cam ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFF060913), RoundedCornerShape(10.dp))
                                                .border(1.dp, Color(0xFF1F2538), RoundedCornerShape(10.dp))
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                // Status blinking-style dot
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (cam.isOurApp) Color(0xFF10B981) else Color(0xFF3B82F6)
                                                        )
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = cam.name,
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = cam.url,
                                                        color = Color(0xFF9CA3AF),
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            
                                            // Action controls
                                            Row {
                                                // Copy URL Button
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setText(AnnotatedString(cam.url))
                                                        Toast.makeText(context, "ক্যামেরা ইউআরএল কপি করা হয়েছে!", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.ContentCopy,
                                                        contentDescription = "Copy Camera URL",
                                                        tint = Color(0xFFA5B4FC),
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                if (!scIsScanning) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "কোনো সক্রিয় ক্যামেরা ডেটা নেই। উপরে খুঁজুন!",
                                            color = Color(0xFF4B5563),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 2.5: Stream Parameter Customization (Resolution, Frame Rate limit)
                item {
                    val context = LocalContext.current
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(width = 1.dp, color = Color(0xFF1F2538), shape = RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1224)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "স্ট্রিমিং কনফিগারেশন সেটআপ (Config)",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("stream_config_heading")
                            )

                            // Resolution control
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "ভিডিও রেজোলিউশন (Resolution):",
                                    color = Color(0xFFA5B4FC),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("High", "Medium", "Low").forEach { resOption ->
                                        val isSelected = (resOption == selectedResolution)
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(
                                                    color = if (isSelected) Color(0xFF00ADB5) else Color(0xFF070B16),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isSelected) Color(0xFF00ADB5) else Color(0xFF1F2538),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    if (WebcamService.selectedResolution.value != resOption) {
                                                        WebcamService.selectedResolution.value = resOption
                                                        WebcamService.addLog("Resolution of video changed locally to: $resOption")
                                                        if (isRunning) {
                                                            // Trigger camera use case update
                                                            val rebindIntent = Intent(context, WebcamService::class.java).apply {
                                                                action = WebcamService.ACTION_START
                                                                putExtra(WebcamService.KEY_PORT, WebcamService.selectedPort.value)
                                                            }
                                                            context.startService(rebindIntent)
                                                        }
                                                    }
                                                }
                                                .padding(vertical = 10.dp)
                                                .testTag("res_option_${resOption.lowercase()}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = when(resOption) {
                                                    "High" -> "High (720p)"
                                                    "Low" -> "Low (240p)"
                                                    else -> "Medium (480p)"
                                                },
                                                color = if (isSelected) Color.Black else Color(0xFF9CA3AF),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            // Frame rate limit control
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "ফ্রেম রেট লিমিট (Target FPS):",
                                    color = Color(0xFFA5B4FC),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(5, 10, 15, 24, 30).forEach { fpsOption ->
                                        val isSelected = (fpsOption == targetFps)
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(
                                                    color = if (isSelected) Color(0xFF00ADB5) else Color(0xFF070B16),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isSelected) Color(0xFF00ADB5) else Color(0xFF1F2538),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    if (WebcamService.targetFps.value != fpsOption) {
                                                        WebcamService.targetFps.value = fpsOption
                                                        WebcamService.addLog("Frame rate throttled locally to: $fpsOption FPS")
                                                    }
                                                }
                                                .padding(vertical = 8.dp)
                                                .testTag("fps_option_$fpsOption"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${fpsOption} FPS",
                                                color = if (isSelected) Color.Black else Color(0xFF9CA3AF),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 3: Live Viewfinder / Preview
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .border(width = 1.dp, color = Color(0xFF1F2538), shape = RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF070B16))
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isRunning) {
                                // Live CameraX viewfinder feed inside the app
                                AndroidView(
                                    factory = { ctx ->
                                        PreviewView(ctx).apply {
                                            scaleType = PreviewView.ScaleType.FILL_CENTER
                                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                            // Assign surface provider to WebcamService
                                            WebcamService.localSurfaceProvider.value = surfaceProvider
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                                )

                                DisposableEffect(Unit) {
                                    onDispose {
                                        // Reset SurfaceProvider upon Activity decomposition / background
                                        WebcamService.localSurfaceProvider.value = null
                                    }
                                }

                                // Overlay camera controls
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    contentAlignment = Alignment.BottomStart
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                                .clip(CircleShape)
                                                .clickable {
                                                    // Trigger direct service flips
                                                    val intent = Intent(context, WebcamService::class.java).apply {
                                                        action = WebcamService.ACTION_START
                                                        putExtra(WebcamService.KEY_PORT, WebcamService.selectedPort.value)
                                                    }
                                                    // Switch state directly
                                                    WebcamService.cameraType.value = if (cameraType == "BACK") "FRONT" else "BACK"
                                                    context.startService(intent)
                                                    WebcamService.addLog("Camera rotated from Dashboard panel.")
                                                }
                                                .padding(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.FlipCameraAndroid,
                                                contentDescription = "Switch Camera Lens",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        if (cameraType == "BACK") {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (flashLightOn) Color(0xFF00ADB5).copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.6f),
                                                        CircleShape
                                                    )
                                                    .clip(CircleShape)
                                                    .clickable {
                                                        // Toggle flashlight via Service triggers
                                                        val isTorch = !flashLightOn
                                                        val intent = Intent(context, WebcamService::class.java).apply {
                                                            action = WebcamService.ACTION_START
                                                            putExtra(WebcamService.KEY_PORT, WebcamService.selectedPort.value)
                                                        }
                                                        WebcamService.flashLightOn.value = isTorch
                                                        context.startService(intent)
                                                        WebcamService.addLog("Flashlight toggled from Dashboard panel.")
                                                    }
                                                    .padding(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.FlashOn,
                                                    contentDescription = "Torch Flashlight",
                                                    tint = if (flashLightOn) Color.Black else Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Overlay badge
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    contentAlignment = Alignment.TopEnd
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "VIEWFINDER ON",
                                            color = Color(0xFF00ADB5),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }
                            } else {
                                // Viewfinder offline state
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.VideocamOff,
                                        contentDescription = null,
                                        tint = Color(0xFF374151),
                                        modifier = Modifier.size(44.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "স্ট্রিমিং প্রিভিউ অফলাইন",
                                        color = Color(0xFF4B5563),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "ক্যামেরা প্রিভিউ দেখতে স্ট্রিমিং চালু করুন",
                                        color = Color(0xFF374151),
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 4: Diagnostics Cards (Viewer count, FPS, Battery, Selected lens detail)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            DiagnosticCard(
                                title = "সংযুক্ত ভিউয়ার",
                                value = "$activeViewers",
                                subtitle = "জন দেখছেন",
                                icon = Icons.Filled.Devices,
                                iconColor = Color(0xFF3B82F6),
                                modifier = Modifier.weight(0.5f)
                            )
                            DiagnosticCard(
                                title = "স্ট্রিমিং স্পিড",
                                value = "$streamFps",
                                subtitle = "FPS",
                                icon = Icons.Filled.Videocam,
                                iconColor = Color(0xFF10B981),
                                modifier = Modifier.weight(0.5f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            DiagnosticCard(
                                title = "ব্যাটারি লেভেল",
                                value = "$batteryPct%",
                                subtitle = if (batteryPct > 50) "পর্যাপ্ত চার্জ" else "চার্জে দিন",
                                icon = Icons.Filled.BatteryChargingFull,
                                iconColor = if (batteryPct > 35) Color(0xFFFBBF24) else Color(0xFFEF4444),
                                modifier = Modifier.weight(0.5f)
                            )
                            DiagnosticCard(
                                title = "ক্যামেরার লেন্স",
                                value = cameraType,
                                subtitle = if (cameraType == "BACK") "মূল ক্যামেরা" else "সেলফি ক্যামেরা",
                                icon = Icons.Filled.FlipCameraAndroid,
                                iconColor = Color(0xFFF472B6),
                                modifier = Modifier.weight(0.5f)
                            )
                        }
                    }
                }

                // Section 5: Real-time Event Logger Console
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .border(width = 1.dp, color = Color(0xFF1F2538), shape = RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF060913)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.List,
                                    contentDescription = "Event Log telemetry icon",
                                    tint = Color(0xFF00ADB5),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "সিস্টেম রিয়েল-টাইম লগ (System Log)",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            if (logs.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "কোনো লগ এন্ট্রি নেই—সার্ভার বন্ধ রয়েছে।",
                                        color = Color(0xFF374151),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(logs) { logMsg ->
                                        Text(
                                            text = logMsg,
                                            color = if (logMsg.contains("Error") || logMsg.contains("failed")) Color(0xFFFCA5A5) else Color(0xFFA5B4FC),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticCard(
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.border(width = 1.dp, color = Color(0xFF1F2538), shape = RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1224)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color(0xFF9CA3AF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = subtitle,
                    color = Color(0xFF4B5563),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(iconColor.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
