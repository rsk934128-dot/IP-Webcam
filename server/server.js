/**
 * Sovereign IP Webcam - MJPEG Stream Router & Controller
 * 
 * A performance-oriented Express.js companion server designed to proxy,
 * router, relay, and distribute motion JPEG (MJPEG) streams from the
 * Sovereign Android IP Webcam.
 */

const express = require('express');
const axios = require('axios');
const http = require('http');
const jpeg = require('jpeg-js');
const WebSocket = require('ws');

const app = express();

// Parse JSON request bodies
app.use(express.json());

const server = http.createServer(app);

// Initialize WebSocket server attached to HTTP server
const wss = new WebSocket.Server({ server });

// Track connected clients
wss.on('connection', (ws) => {
  log('[WebSocket] Client dashboard connected');
  // Send connection confirmation with current configuration state
  ws.send(JSON.stringify({ 
    type: 'connected', 
    data: { enabled: motionConfig.enabled, isMotionDetected: motionState.isMotionDetected } 
  }));
  ws.on('close', () => {
    log('[WebSocket] Client dashboard disconnected');
  });
});

// Helper function to broadcast messages to all connected WebSockets
function broadcast(message) {
  if (!wss || !wss.clients) return;
  wss.clients.forEach((client) => {
    if (client.readyState === WebSocket.OPEN) {
      try {
        client.send(JSON.stringify(message));
      } catch (err) {
        log(`[WebSocket] Fail to broadcast message: ${err.message}`);
      }
    }
  });
}

// Use configuration from environment or default parameters
const PORT = process.env.PORT || 3000;
const WEBCAM_URL = process.env.WEBCAM_URL || 'http://localhost:8080';

// Motion detection configuration parameters
let motionConfig = {
  enabled: true,
  sensitivity: 1.5,   // Minimum % of changed pixels to trigger motion
  threshold: 25,       // Pixel intensity difference threshold (0-255)
  stride: 8,           // Pixel sub-sampling step size (must be >= 1)
  samplingInterval: 250 // Millisecond rate for analyzing frames (e.g. 250ms = 4 fps)
};

// Motion detector tracking state
let motionState = {
  currentIntensity: 0,
  isMotionDetected: false,
  lastEventTime: null,
  recentEvents: [] // Historic alerts: { id, startTime, endTime, maxIntensity, durationSecs, active }
};

let motionStreamActive = false;
let lastFrameBuffer = null;
let lastAnalysisTime = 0;

// Global server status cache
let currentStats = {
  viewerCount: 0,
  fps: 0,
  batteryLevel: 100,
  cameraType: 'Unknown',
  uptimeSecs: 0,
  activeRelays: 0
};

// Track active proxy client connections
const activeRelays = new Set();

// Track active client screen browsers for cloud push relay
const liveClients = new Set();
let pendingCommands = [];

// Track stream relay activation state
let isStreamRunning = true;

// Utility helper to clean and format target URLs
function getTargetUrl(path) {
  const cleanBase = WEBCAM_URL.replace(/\/+$/, '');
  return `${cleanBase}${path}`;
}

// Log status reporting
function log(msg) {
  console.log(`[Router Server] [${new Date().toISOString()}] ${msg}`);
}

// Serve a visually-stunning control dashboard as default home index
    app.get('/', (req, res) => {
  res.send(`
    <!DOCTYPE html>
    <html lang="en" class="dark">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <meta name="theme-color" content="#00ADB5">
        <meta name="apple-mobile-web-app-capable" content="yes">
        <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
        <title>Sovereign MJPEG Stream Router Console</title>
        <link rel="manifest" href="/manifest.json">
        <link rel="apple-touch-icon" href="/logo.svg">
        <script src="https://cdn.tailwindcss.com"></script>
        <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
        <style>
            body { background-color: #0d1117; color: #c9d1d9; }
        </style>
    </head>
    <body class="min-h-screen flex flex-col antialiased font-sans">
        <!-- Header -->
        <header class="border-b border-zinc-800 bg-zinc-950/80 backdrop-blur-md p-4 sticky top-0 z-50">
            <div class="max-w-6xl mx-auto flex justify-between items-center">
                <div class="flex items-center space-x-3">
                    <div class="bg-teal-500 text-black px-2.5 py-1 rounded-md font-mono font-black text-xs tracking-wider animate-pulse">
                        ROUTER LIVE
                    </div>
                    <h1 class="text-lg font-black tracking-tight text-white flex items-center">
                        <i class="fa-solid fa-server mr-2 text-teal-400"></i> Sovereign Stream Router
                    </h1>
                </div>
                <div class="text-xs font-mono text-zinc-400 flex items-center space-x-4">
                    <span>Target: <code class="bg-zinc-800/80 px-2 py-0.5 rounded text-teal-300">${WEBCAM_URL}</code></span>
                    <span id="uptime">Uptime: 00:00:00</span>
                </div>
            </div>
        </header>

        <!-- Main Workspace -->
        <main class="flex-grow max-w-6xl w-full mx-auto p-4 grid grid-cols-1 md:grid-cols-3 gap-6">
            <!-- Feed Viewer -->
            <div class="md:col-span-2 space-y-4">
                <div class="relative bg-black rounded-2xl border border-zinc-800 overflow-hidden shadow-2xl aspect-video flex items-center justify-center">
                    <img id="stream" src="/live" class="w-full h-full object-contain" alt="Routed Live Stream Feed">
                    <div id="stream-placeholder" class="absolute inset-0 bg-zinc-950 flex flex-col items-center justify-center space-y-3 hidden">
                        <div class="w-16 h-16 rounded-full bg-zinc-900 border border-zinc-800 flex items-center justify-center shadow-inner">
                            <i class="fa-solid fa-video-slash text-2xl text-zinc-500 animate-pulse"></i>
                        </div>
                        <div class="text-center">
                            <p class="text-zinc-200 font-bold text-sm">Stream Feed Stopped</p>
                            <p class="text-zinc-500 text-xs">Relay proxy is currently suspended on the router</p>
                        </div>
                    </div>
                    <div id="stream-badge" class="absolute top-4 left-4 bg-black/60 backdrop-blur-md text-teal-400 px-3 py-1 rounded-full text-xs font-mono font-bold flex items-center space-x-2">
                        <span class="w-2 h-2 rounded-full bg-teal-500 animate-ping" id="stream-pulse"></span>
                        <span id="stream-badge-text">Routed Webcam Feed</span>
                    </div>
                    <!-- Motion Alert Overlay -->
                    <div id="motion-overlay" class="absolute top-4 right-4 bg-red-650/90 text-white px-3 py-1 rounded-full text-xs font-mono font-bold flex items-center space-x-2 border border-red-500 shadow-lg animate-bounce hidden" style="background-color: rgba(220, 38, 38, 0.9);">
                        <span class="w-2 h-2 rounded-full bg-white animate-ping"></span>
                        <span>MOTION DETECTED</span>
                    </div>
                </div>
                <div class="bg-zinc-900/40 border border-zinc-800/80 rounded-xl p-4 text-xs text-zinc-400 flex items-start space-x-3">
                    <i class="fa-solid fa-circle-nodes text-teal-500 mt-0.5"></i>
                    <p>This Node.js server relays the high-performance MJPEG stream from your Android camera. 
                    Copy <code class="bg-zinc-900 px-1.5 py-0.5 rounded text-white font-semibold">http://localhost:${PORT}/live</code> directly into OBS, VLC, or Home Assistant to embed the routed feed.</p>
                </div>

                <!-- Bengali Installation & Mobile Setup Section (M3 Minimalist Design) -->
                <div class="bg-gradient-to-r from-zinc-950 via-zinc-900 to-zinc-950 border border-zinc-800 rounded-2xl p-5 shadow-xl space-y-4">
                    <div class="flex items-center justify-between">
                        <div class="flex items-center space-x-2.5">
                            <span class="w-2.5 h-2.5 rounded-full bg-teal-400 animate-pulse"></span>
                            <h3 class="text-xs font-black tracking-wide text-white uppercase">ওয়েবসাইট থেকে অ্যাপ ইনস্টল করুন (Install App)</h3>
                        </div>
                        <span class="text-[9px] bg-zinc-800 px-2 py-0.5 rounded text-zinc-400 font-mono font-bold uppercase">STANDALONE SETUP</span>
                    </div>
                    
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <!-- Option 1: Web Installer (PWA) -->
                        <div class="bg-zinc-900/60 border border-zinc-800 p-4 rounded-xl flex flex-col justify-between space-y-3">
                            <div class="space-y-1">
                                <span class="bg-teal-500/10 text-teal-400 px-2 py-0.5 rounded text-[8px] font-bold border border-teal-500/20 shadow-sm inline-block uppercase">OP-1: STANDALONE APP</span>
                                <h4 class="text-white text-xs font-bold pt-1 flex items-center">
                                    <i class="fa-solid fa-mobile-screen mr-1.5 text-teal-400"></i>
                                    ড্যাশবোর্ড অ্যাপ ইনস্টল (PWA App)
                                </h4>
                                <p class="text-[11px] text-zinc-400 leading-relaxed">পিসি, ক্রোমবুক বা এন্ড্রয়েড ফোনের ড্রয়ারে সরাসরি অ্যাপের মতো ব্যবহার করতে ড্যাশবোর্ডটি সেভ করে রাখুন।</p>
                            </div>
                            <button id="pwa-install-btn" class="w-full py-2.5 px-4 bg-teal-500 text-black hover:bg-teal-400 font-black transition-all duration-200 rounded-lg text-xs shadow-md flex items-center justify-center space-x-1.5 cursor-pointer opacity-50" disabled>
                                <i class="fa-solid fa-cloud-arrow-down"></i>
                                <span>অ্যাপ ইনস্টল করুন (Install App)</span>
                            </button>
                        </div>

                        <!-- Option 2: Camera APK Downloader -->
                        <div class="bg-zinc-900/60 border border-zinc-800 p-4 rounded-xl flex flex-col justify-between space-y-3">
                            <div class="space-y-1">
                                <span class="bg-indigo-505/10 text-indigo-400 px-2 py-0.5 rounded text-[8px] font-bold border border-indigo-500/20 shadow-sm inline-block uppercase">OP-2: CAMERA SOURCE APK</span>
                                <h4 class="text-white text-xs font-bold pt-1 flex items-center">
                                    <i class="fa-brands fa-android mr-1.5 text-indigo-400"></i>
                                    এন্ড্রয়েড ক্যামেরা ট্রান্সমিটার (APK)
                                </h4>
                                <p class="text-[11px] text-zinc-400 leading-relaxed">আপনার এন্ড্রয়েড ফোনটিকে সিকিউরিটি ক্যাবলহীন আইপি ও মোশন-ডিটেকশন ক্যামেরায় রূপান্তর করতে ক্যামেরা অ্যাপটি ডাউনলোড করুন।</p>
                            </div>
                            <a href="/download-apk" class="w-full py-2.5 px-4 bg-[#1f2d4d] hover:bg-[#2a3c66] text-indigo-300 hover:text-indigo-200 font-bold transition-all duration-200 rounded-lg text-xs flex items-center justify-center space-x-1.5 border border-indigo-800/40">
                                <i class="fa-solid fa-download"></i>
                                <span>ক্যামেরা অ্যাপ ডাউনলোড (Download APK)</span>
                            </a>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Diagnostics Panel -->
            <div class="space-y-6">
                <!-- Stream Relay Controller -->
                <div class="bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl space-y-4">
                    <div class="flex justify-between items-center">
                        <h3 class="text-xs font-semibold tracking-wider uppercase text-zinc-400">Stream Relay Controller</h3>
                        <span id="relay-status-badge" class="px-2 py-0.5 rounded text-[10px] font-bold font-mono tracking-wider bg-teal-500/15 text-teal-400 border border-teal-500/20">
                            ACTIVE
                        </span>
                    </div>
                    <div class="grid grid-cols-2 gap-3">
                        <button id="btn-start-stream" onclick="controlRelayStream('start')" class="flex items-center justify-center space-x-2 py-3 px-4 bg-teal-500 text-black hover:bg-teal-400 font-extrabold transition-all duration-200 rounded-xl text-xs shadow-lg shadow-teal-500/10">
                            <i class="fa-solid fa-play"></i>
                            <span>Start Feed</span>
                        </button>
                        <button id="btn-stop-stream" onclick="controlRelayStream('stop')" class="flex items-center justify-center space-x-2 py-3 px-4 bg-zinc-805/80 hover:bg-zinc-700/80 border border-zinc-800 font-extrabold transition-all duration-200 rounded-xl text-xs text-zinc-300">
                            <i class="fa-solid fa-square text-red-500"></i>
                            <span>Stop Feed</span>
                        </button>
                    </div>
                </div>

                <!-- Server-Side Motion Radar -->
                <div class="bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl space-y-4">
                    <div class="flex justify-between items-center">
                        <div class="flex items-center space-x-2">
                            <i class="fa-solid fa-person-running text-orange-400 text-sm"></i>
                            <h3 class="text-xs font-semibold tracking-wider uppercase text-zinc-400">Motion Radar</h3>
                        </div>
                        <label class="relative inline-flex items-center cursor-pointer select-none">
                            <input type="checkbox" id="motion-enabled-toggle" onchange="toggleMotionDetection()" class="sr-only peer" checked>
                            <div class="w-9 h-5 bg-zinc-800 rounded-full peer peer-focus:ring-2 peer-focus:ring-orange-500/30 peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-zinc-400 after:border-zinc-350 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-orange-500 peer-checked:after:bg-white border border-zinc-700"></div>
                        </label>
                    </div>
                    
                    <!-- Live Motion Meter -->
                    <div class="space-y-1.5">
                        <div class="flex justify-between text-[11px] font-mono">
                            <span class="text-zinc-500">Live Difference Intensity</span>
                            <span class="text-orange-400 font-bold" id="motion-live-intensity">0.0%</span>
                        </div>
                        <div class="w-full bg-zinc-950 h-2.5 rounded-full overflow-hidden border border-zinc-900">
                            <div id="motion-intensity-bar" class="bg-gradient-to-r from-orange-600 via-orange-400 to-red-500 h-full w-[0%] transition-all duration-150"></div>
                        </div>
                    </div>

                    <!-- Adjustable Parameters -->
                    <div class="border-t border-zinc-800/60 pt-3 space-y-3">
                        <div>
                            <div class="flex justify-between text-[11px] mb-1 font-mono">
                                <span class="text-zinc-400">Sensitivity Trigger:</span>
                                <span class="text-teal-400 font-bold" id="label-sensitivity">1.5%</span>
                            </div>
                            <input id="slider-sensitivity" type="range" min="0.2" max="10" step="0.1" value="1.5" oninput="updateMotionConfig()" class="w-full h-1 bg-zinc-800 rounded-lg appearance-none cursor-pointer accent-teal-400">
                        </div>
                        <div>
                            <div class="flex justify-between text-[11px] mb-1 font-mono">
                                <span class="text-zinc-400">Pixel Color Threshold:</span>
                                <span class="text-teal-400 font-bold" id="label-threshold">25</span>
                            </div>
                            <input id="slider-threshold" type="range" min="5" max="100" step="1" value="25" oninput="updateMotionConfig()" class="w-full h-1 bg-zinc-800 rounded-lg appearance-none cursor-pointer accent-teal-400">
                        </div>
                    </div>

                    <!-- Clear Log & Events List -->
                    <div class="border-t border-zinc-800/60 pt-3 space-y-2">
                        <div class="flex justify-between items-center text-[10px] font-semibold tracking-wider text-zinc-500 uppercase">
                            <span>Activity Log (Last 15)</span>
                            <button onclick="clearMotionLogs()" class="hover:text-zinc-300 transition-colors uppercase flex items-center space-x-1">
                                <i class="fa-solid fa-trash-can text-[9px] text-zinc-500"></i>
                                <span class="text-[9px]">Clear</span>
                            </button>
                        </div>
                        <div id="motion-events-list" class="max-h-40 overflow-y-auto space-y-1.5 pr-1 font-mono scrollbar-thin scrollbar-thumb-zinc-800 text-xs">
                            <div class="text-center py-4 text-xs text-zinc-600">No activity recorded yet</div>
                        </div>
                    </div>
                </div>

                <!-- Stream Configuration Panel -->
                <div class="bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl space-y-4">
                    <div class="flex items-center space-x-2">
                        <i class="fa-solid fa-sliders text-teal-400 text-sm"></i>
                        <h3 class="text-xs font-semibold tracking-wider uppercase text-zinc-400">Stream Parameter Setup</h3>
                    </div>
                    
                    <!-- Video Resolution Selection -->
                    <div class="space-y-2">
                        <span class="text-[11px] font-mono text-zinc-500">Video Resolution</span>
                        <div class="grid grid-cols-3 gap-2">
                            <button id="res-Low" onclick="setStreamResolution('Low')" class="py-2 bg-zinc-800/65 border border-zinc-800 hover:border-teal-500/40 text-[10px] uppercase font-bold tracking-wider rounded-xl transition-all duration-200 text-zinc-400">Low (240p)</button>
                            <button id="res-Medium" onclick="setStreamResolution('Medium')" class="py-2 bg-teal-500 text-black text-[10px] uppercase font-bold tracking-wider rounded-xl transition-all duration-200">Medium (480p)</button>
                            <button id="res-High" onclick="setStreamResolution('High')" class="py-2 bg-zinc-800/65 border border-zinc-800 hover:border-teal-500/40 text-[10px] uppercase font-bold tracking-wider rounded-xl transition-all duration-200 text-zinc-400">High (720p)</button>
                        </div>
                    </div>

                    <!-- Target Frame Rate (FPS Limiter) -->
                    <div class="space-y-2">
                        <span class="text-[11px] font-mono text-zinc-500">Frame Rate limit (Throttled)</span>
                        <div class="grid grid-cols-5 gap-1.5">
                            <button id="fps-5" onclick="setStreamFps(5)" class="py-2 bg-zinc-800/65 border border-zinc-800 hover:border-teal-500/40 text-[9px] font-bold rounded-lg transition-all duration-200 text-zinc-400">5</button>
                            <button id="fps-10" onclick="setStreamFps(10)" class="py-2 bg-zinc-800/65 border border-zinc-800 hover:border-teal-500/40 text-[9px] font-bold rounded-lg transition-all duration-200 text-zinc-400">10</button>
                            <button id="fps-15" onclick="setStreamFps(15)" class="py-2 bg-teal-500 text-black text-[9px] font-bold rounded-lg transition-all duration-200">15</button>
                            <button id="fps-24" onclick="setStreamFps(24)" class="py-2 bg-zinc-800/65 border border-zinc-800 hover:border-teal-500/40 text-[9px] font-bold rounded-lg transition-all duration-200 text-zinc-400">24</button>
                            <button id="fps-30" onclick="setStreamFps(30)" class="py-2 bg-zinc-800/65 border border-zinc-800 hover:border-teal-500/40 text-[9px] font-bold rounded-lg transition-all duration-200 text-zinc-400">30</button>
                        </div>
                    </div>
                </div>

                <!-- Device Controls -->
                <div class="bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl space-y-4">
                    <h3 class="text-xs font-semibold tracking-wider uppercase text-zinc-400">Proxied Controls</h3>
                    <div class="grid grid-cols-2 gap-4">
                        <button onclick="triggerControl('/api/toggle-flash')" class="flex flex-col items-center justify-center p-4 bg-zinc-800/30 hover:bg-teal-500/10 hover:border-teal-500/50 border border-zinc-800 transition-all duration-300 rounded-xl group text-teal-500">
                            <i class="fa-solid fa-lightbulb text-2xl mb-2 group-hover:scale-110 transition-transform"></i>
                            <span class="text-xs font-semibold text-zinc-300">Flashlight</span>
                        </button>
                        <button onclick="triggerControl('/api/switch-camera')" class="flex flex-col items-center justify-center p-4 bg-zinc-800/30 hover:bg-pink-500/10 hover:border-pink-500/50 border border-zinc-800 transition-all duration-300 rounded-xl group text-pink-500">
                            <i class="fa-solid fa-camera-rotate text-2xl mb-2 group-hover:scale-110 transition-transform"></i>
                            <span class="text-xs font-semibold text-zinc-300">Flip Camera</span>
                        </button>
                    </div>
                </div>

                <!-- Live Diagnostis -->
                <div class="bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl space-y-4">
                    <h3 class="text-xs font-semibold tracking-wider uppercase text-zinc-400">Router Metrics</h3>
                    <div class="grid grid-cols-2 gap-4">
                        <div class="p-4 bg-zinc-800/20 rounded-xl border border-zinc-800/40">
                            <span class="block text-[10px] text-zinc-400 mb-1">Active Streams</span>
                            <div class="flex items-baseline space-x-1.5">
                                <span class="text-2xl font-bold text-white font-mono" id="relay-count">0</span>
                                <span class="text-[9px] text-teal-400 uppercase">Relays</span>
                            </div>
                        </div>
                        <div class="p-4 bg-zinc-800/20 rounded-xl border border-zinc-800/40">
                            <span class="block text-[10px] text-zinc-400 mb-1">Android Viewers</span>
                            <div class="flex items-baseline space-x-1.5">
                                <span class="text-2xl font-bold text-white font-mono" id="viewer-count">0</span>
                                <span class="text-[9px] text-zinc-400">Devices</span>
                            </div>
                        </div>
                        <div class="p-4 bg-zinc-800/20 rounded-xl border border-zinc-800/40">
                            <span class="block text-[10px] text-zinc-400 mb-1">FPS Estimate</span>
                            <div class="flex items-baseline space-x-1.5">
                                <span class="text-2xl font-bold text-white font-mono" id="fps">0</span>
                                <span class="text-[9px] text-zinc-500">FPS</span>
                            </div>
                        </div>
                        <div class="p-4 bg-zinc-800/20 rounded-xl border border-zinc-800/40">
                            <span class="block text-[10px] text-zinc-400 mb-1">Android Battery</span>
                            <div class="flex items-baseline space-x-1.5">
                                <span class="text-2xl font-bold text-white font-mono" id="battery">--%</span>
                                <i id="battery-icon" class="fa-solid fa-battery-three-quarters text-sm text-green-400"></i>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </main>

        <!-- Footer -->
        <footer class="bg-zinc-950 border-t border-zinc-900 p-4 text-center text-xs text-zinc-500 font-mono">
            &copy; 2026 Sovereign Stream Router. Express & Axio Distributed Relay Architecture.
        </footer>

        <script>
            let startTime = Date.now();
            let streamIsRunning = true;

            function updateUptime() {
                let elapsedSecs = Math.floor((Date.now() - startTime) / 1000);
                let hrs = String(Math.floor(elapsedSecs / 3600)).padStart(2, '0');
                let mins = String(Math.floor((elapsedSecs % 3600) / 60)).padStart(2, '0');
                let secs = String(elapsedSecs % 60).padStart(2, '0');
                document.getElementById('uptime').innerText = "Router Uptime: " + hrs + ":" + mins + ":" + secs;
            }
            setInterval(updateUptime, 1000);

            async function controlRelayStream(action) {
                try {
                    let r = await fetch('/api/stream/' + action);
                    let res = await r.json();
                    if(res.success) {
                        streamIsRunning = res.active;
                        updateStreamUI();
                        fetchStats();
                    }
                } catch(e) {
                    console.error("Could not control stream relay.", e);
                }
            }

            function updateStreamUI() {
                const streamImg = document.getElementById('stream');
                const placeholder = document.getElementById('stream-placeholder');
                const pulse = document.getElementById('stream-pulse');
                const badgeText = document.getElementById('stream-badge-text');
                const relayStatusBadge = document.getElementById('relay-status-badge');
                
                const btnStart = document.getElementById('btn-start-stream');
                const btnStop = document.getElementById('btn-stop-stream');

                if (streamIsRunning) {
                    if (!streamImg.src.includes('/live')) {
                        streamImg.src = '/live?t=' + Date.now();
                    }
                    placeholder.classList.add('hidden');
                    pulse.classList.add('bg-teal-500');
                    pulse.classList.remove('bg-zinc-650');
                    pulse.classList.add('animate-ping');
                    badgeText.innerText = "Routed Webcam Feed";
                    badgeText.className = "text-teal-400";
                    
                    relayStatusBadge.innerText = "ACTIVE";
                    relayStatusBadge.className = "px-2 py-0.5 rounded text-[10px] font-bold font-mono tracking-wider bg-teal-500/15 text-teal-400 border border-teal-500/20";

                    btnStart.className = "flex items-center justify-center space-x-2 py-3 px-4 bg-teal-500 text-black hover:bg-teal-400 font-extrabold transition-all duration-200 rounded-xl text-xs shadow-lg shadow-teal-500/10";
                    btnStop.className = "flex items-center justify-center space-x-2 py-3 px-4 bg-zinc-800/80 hover:bg-zinc-700/80 border border-zinc-850 font-extrabold transition-all duration-200 rounded-xl text-xs text-zinc-300";
                } else {
                    streamImg.src = "data:image/svg+xml;charset=utf-8,%3Csvg xmlns%3D'http%3D%2F%2Fwww.w3.org%2F2000%2Fsvg' viewBox%3D'0 0 16 9'%2F%3E";
                    placeholder.classList.remove('hidden');
                    pulse.classList.remove('bg-teal-500');
                    pulse.classList.add('bg-zinc-650');
                    pulse.classList.remove('animate-ping');
                    badgeText.innerText = "Feed Stopped";
                    badgeText.className = "text-zinc-500";

                    relayStatusBadge.innerText = "STOPPED";
                    relayStatusBadge.className = "px-2 py-0.5 rounded text-[10px] font-bold font-mono tracking-wider bg-red-500/15 text-red-500 border border-red-500/20";
                    
                    btnStart.className = "flex items-center justify-center space-x-2 py-3 px-4 bg-zinc-800/80 hover:bg-zinc-700/80 border border-zinc-850 transition-all duration-200 rounded-xl text-xs text-zinc-300";
                    btnStop.className = "flex items-center justify-center space-x-2 py-3 px-4 bg-red-500 text-white hover:bg-red-400 font-extrabold transition-all duration-200 rounded-xl text-xs shadow-lg shadow-red-500/10";
                }
            }

            async function fetchStats() {
                try {
                    let response = await fetch('/api/stats');
                    let data = await response.json();
                    
                    document.getElementById('relay-count').innerText = data.activeRelays;
                    document.getElementById('viewer-count').innerText = data.viewerCount;
                    document.getElementById('fps').innerText = data.fps;
                    document.getElementById('battery').innerText = data.batteryLevel + '%';

                    if (typeof data.isStreamRunning !== 'undefined' && data.isStreamRunning !== streamIsRunning) {
                        streamIsRunning = data.isStreamRunning;
                        updateStreamUI();
                    }
                    
                    let bIcon = document.getElementById('battery-icon');
                    if(data.batteryLevel > 75) {
                        bIcon.className = "fa-solid fa-battery-full text-green-500";
                    } else if(data.batteryLevel > 35) {
                        bIcon.className = "fa-solid fa-battery-half text-yellow-500";
                    } else {
                        bIcon.className = "fa-solid fa-battery-empty text-red-500 animate-pulse";
                    }
                } catch(err) {
                    console.error("Failed to retrieve diagnostics stats.", err);
                }
            }
            setInterval(fetchStats, 2000);
            fetchStats();

            async function triggerControl(endpoint) {
                try {
                    let r = await fetch(endpoint);
                    let res = await r.json();
                    if(res.success) {
                        setTimeout(fetchStats, 500);
                    }
                } catch(e) {
                    console.error("Could not trigger camera control over proxy.", e);
                }
            }

            // Motion Detection Client-Side Logic
            async function fetchMotionState() {
                try {
                    let r = await fetch('/api/motion/status');
                    let data = await r.json();
                    
                    document.getElementById('motion-enabled-toggle').checked = data.enabled;
                    document.getElementById('motion-live-intensity').innerText = data.currentIntensity.toFixed(1) + '%';
                    
                    const bar = document.getElementById('motion-intensity-bar');
                    bar.style.width = Math.min(data.currentIntensity * 15, 100) + '%';
                    
                    if (data.isMotionDetected) {
                        bar.className = "bg-gradient-to-r from-red-600 via-orange-500 to-red-400 h-full transition-all duration-150 shadow-[0_0_8px_rgba(239,68,68,0.5)]";
                        document.getElementById('motion-overlay').classList.remove('hidden');
                    } else {
                        bar.className = "bg-gradient-to-r from-teal-500 to-orange-500 h-full transition-all duration-150";
                        document.getElementById('motion-overlay').classList.add('hidden');
                    }
                    
                    const list = document.getElementById('motion-events-list');
                    if (!data.recentEvents || data.recentEvents.length === 0) {
                        list.innerHTML = `<div class="text-center py-4 text-xs text-zinc-650">No activity recorded yet</div>`;
                    } else {
                        list.innerHTML = data.recentEvents.map(ev => {
                            const startTimeStr = new Date(ev.startTime).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit', second:'2-digit'});
                            const durationText = ev.active ? 
                                `<span class="text-red-500 animate-pulse font-bold">Live (${ev.durationSecs}s)</span>` : 
                                `<span>${ev.durationSecs}s</span>`;
                                
                            const statusBadge = ev.active ? 
                                `<span class="bg-red-505/10 text-red-400 px-1.5 py-0.5 rounded text-[8px] font-bold border border-red-500/20 flex items-center space-x-1 animate-pulse">
                                    <span class="w-1.5 h-1.5 rounded-full bg-red-400"></span><span>ACTIVE</span>
                                 </span>` : 
                                `<span class="bg-zinc-800 text-zinc-400 px-1.5 py-0.5 rounded text-[8px] font-medium border border-zinc-700/60">ENDED</span>`;

                            return `
                                <div class="bg-zinc-950/60 border border-zinc-805/60 rounded-lg p-2 flex justify-between items-center space-x-2">
                                    <div class="space-y-0.5 flex-grow">
                                        <div class="flex items-center space-x-1.5">
                                            <span class="text-zinc-200 font-semibold">${startTimeStr}</span>
                                            ${statusBadge}
                                        </div>
                                        <div class="text-[9px] text-zinc-500 flex items-center space-x-2">
                                            <span>Duration: ${durationText}</span>
                                            <span>•</span>
                                            <span>Max Int: <strong class="text-orange-400">${ev.maxIntensity.toFixed(1)}%</strong></span>
                                        </div>
                                    </div>
                                    <div class="text-[12px] text-zinc-600">
                                        <i class="fa-solid ${ev.active ? 'fa-triangle-exclamation text-red-500 animate-pulse' : 'fa-circle-check text-zinc-700'}"></i>
                                    </div>
                                </div>
                            `;
                        }).join('');
                    }
                } catch (e) {
                    console.error("Could not fetch motion status.", e);
                }
            }

            async function initMotionConfig() {
                try {
                    let r = await fetch('/api/motion/config');
                    let config = await r.json();
                    
                    document.getElementById('slider-sensitivity').value = config.sensitivity;
                    document.getElementById('label-sensitivity').innerText = config.sensitivity.toFixed(1) + '%';
                    
                    document.getElementById('slider-threshold').value = config.threshold;
                    document.getElementById('label-threshold').innerText = config.threshold;
                    
                    document.getElementById('motion-enabled-toggle').checked = config.enabled;
                } catch(e) {
                    console.error("Err loading motion config", e);
                }
            }

            async function updateMotionConfig() {
                const sensitivity = parseFloat(document.getElementById('slider-sensitivity').value);
                const threshold = parseInt(document.getElementById('slider-threshold').value);
                
                document.getElementById('label-sensitivity').innerText = sensitivity.toFixed(1) + '%';
                document.getElementById('label-threshold').innerText = threshold;
                
                try {
                    await fetch('/api/motion/config', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ sensitivity, threshold })
                    });
                } catch (e) {
                    console.error("Failed to commit motion config", e);
                }
            }

            async function toggleMotionDetection() {
                const enabled = document.getElementById('motion-enabled-toggle').checked;
                try {
                    await fetch('/api/motion/config', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ enabled })
                    });
                } catch (e) {
                    console.error("Failed to toggle motion detection", e);
                }
            }

            async function clearMotionLogs() {
                try {
                    let r = await fetch('/api/motion/events', { method: 'DELETE' });
                    let res = await r.json();
                    if (res.success) {
                        fetchMotionState();
                    }
                } catch (e) {
                    console.error("Failed to clear logs", e);
                }
            }

            let currentResolution = "Medium";
            let currentTargetFps = 15;

            async function initStreamConfig() {
                try {
                    let r = await fetch('/api/stream/config');
                    let config = await r.json();
                    
                    if (config.success) {
                        currentResolution = config.resolution;
                        currentTargetFps = config.targetFps;
                        updateConfigUI();
                    }
                } catch(e) {
                    console.error("Err loading stream config", e);
                }
            }

            function updateConfigUI() {
                // Resolution Buttons UI
                ["Low", "Medium", "High"].forEach(r => {
                    const btn = document.getElementById('res-' + r);
                    if (!btn) return;
                    if (r === currentResolution) {
                        btn.className = "py-2 bg-teal-500 text-black text-[10px] uppercase font-bold tracking-wider rounded-xl transition-all duration-200";
                    } else {
                        btn.className = "py-2 bg-zinc-800/65 border border-zinc-800 hover:border-teal-500/40 text-[10px] uppercase font-bold tracking-wider rounded-xl transition-all duration-200 text-zinc-400";
                    }
                });

                // FPS Buttons UI
                [5, 10, 15, 24, 30].forEach(f => {
                    const btn = document.getElementById('fps-' + f);
                    if (!btn) return;
                    if (f === currentTargetFps) {
                        btn.className = "py-2 bg-teal-500 text-black text-[9px] font-bold rounded-lg transition-all duration-200";
                    } else {
                        btn.className = "py-2 bg-zinc-800/65 border border-zinc-800 hover:border-teal-500/40 text-[9px] font-bold rounded-lg transition-all duration-200 text-zinc-400";
                    }
                });
            }

            async function setStreamResolution(resVal) {
                try {
                    let r = await fetch('/api/stream/config', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ resolution: resVal })
                    });
                    let data = await r.json();
                    if (data.success) {
                        currentResolution = data.resolution;
                        updateConfigUI();
                    }
                } catch(e) {
                    console.error("Failed to update resolution", e);
                }
            }

            async function setStreamFps(fpsVal) {
                try {
                    let r = await fetch('/api/stream/config', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ targetFps: fpsVal })
                    });
                    let data = await r.json();
                    if (data.success) {
                        currentTargetFps = data.targetFps;
                        updateConfigUI();
                    }
                } catch(e) {
                    console.error("Failed to update FPS config", e);
                }
            }

            // WebSocket and Real-time alerts integration
            let ws;
            function connectWS() {
                const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                const wsUrl = `${protocol}//${window.location.host}`;
                console.log('Connecting to WebSocket:', wsUrl);
                
                ws = new WebSocket(wsUrl);
                
                ws.onopen = () => {
                    console.log('WebSocket connection established.');
                };
                
                ws.onmessage = (event) => {
                    try {
                        const message = JSON.parse(event.data);
                        if (message.type === 'intensity_update') {
                            renderMotionData(message.data);
                        }
                    } catch (e) {
                        console.error('Error handling WebSocket frame:', e);
                    }
                };
                
                ws.onclose = () => {
                    console.log('WebSocket closed. Reconnecting in 3 seconds...');
                    setTimeout(connectWS, 3000);
                };
                
                ws.onerror = (err) => {
                    console.error('WebSocket Error:', err);
                };
            }

            function renderMotionData(data) {
                // Update live intensity percentage text
                document.getElementById('motion-live-intensity').innerText = data.intensity.toFixed(1) + '%';
                
                // Update progress bar length with orange/teal colors
                const bar = document.getElementById('motion-intensity-bar');
                bar.style.width = Math.min(data.intensity * 15, 100) + '%';
                
                // Show or hide the MOTION DETECTED alert banner overlay
                if (data.isMotionDetected) {
                    bar.className = "bg-gradient-to-r from-red-650 via-orange-500 to-red-400 h-full transition-all duration-150 shadow-[0_0_8px_rgba(239,68,68,0.5)]";
                    document.getElementById('motion-overlay').classList.remove('hidden');
                } else {
                    bar.className = "bg-gradient-to-r from-teal-500 to-orange-500 h-full transition-all duration-150";
                    document.getElementById('motion-overlay').classList.add('hidden');
                }
                
                // Render list of recent events directly from state message
                const list = document.getElementById('motion-events-list');
                if (!data.recentEvents || data.recentEvents.length === 0) {
                    list.innerHTML = `<div class="text-center py-4 text-xs text-zinc-650">No activity recorded yet</div>`;
                } else {
                    list.innerHTML = data.recentEvents.map(ev => {
                        const startTimeStr = new Date(ev.startTime).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit', second:'2-digit'});
                        const durationText = ev.active ? 
                            `<span class="text-red-500 animate-pulse font-bold">Live (${ev.durationSecs}s)</span>` : 
                            `<span>${ev.durationSecs}s</span>`;
                            
                        const statusBadge = ev.active ? 
                            `<span class="bg-red-500/10 text-red-500 px-1.5 py-0.5 rounded text-[8px] font-bold border border-red-500/20 flex items-center space-x-1 animate-pulse">
                                <span class="w-1.5 h-1.5 rounded-full bg-red-500"></span><span>ACTIVE</span>
                             </span>` : 
                            `<span class="bg-zinc-800 text-zinc-400 px-1.5 py-0.5 rounded text-[8px] font-medium border border-zinc-700/60">ENDED</span>`;

                        return `
                            <div class="bg-zinc-950/60 border border-zinc-800/60 rounded-lg p-2 flex justify-between items-center space-x-2">
                                <div class="space-y-0.5 flex-grow">
                                    <div class="flex items-center space-x-1.5">
                                        <span class="text-zinc-200 font-semibold">${startTimeStr}</span>
                                        ${statusBadge}
                                    </div>
                                    <div class="text-[9px] text-zinc-500 flex items-center space-x-2">
                                        <span>Duration: ${durationText}</span>
                                        <span>•</span>
                                        <span>Max Int: <strong class="text-orange-400">${ev.maxIntensity.toFixed(1)}%</strong></span>
                                    </div>
                                </div>
                                <div class="text-[12px] text-zinc-600">
                                    <i class="fa-solid ${ev.active ? 'fa-triangle-exclamation text-red-500 animate-pulse' : 'fa-circle-check text-zinc-700'}"></i>
                                </div>
                            </div>
                        `;
                    }).join('');
                }
            }

            // PWA beforeinstallprompt interception logic
            let deferredPrompt;
            const pwaBtn = document.getElementById('pwa-install-btn');
            
            window.addEventListener('beforeinstallprompt', (e) => {
                // Prevent standard micro-prompting bar
                e.preventDefault();
                // Store the custom event instance 
                deferredPrompt = e;
                // Reveal the install panel button in UI
                pwaBtn.classList.remove('opacity-50');
                pwaBtn.disabled = false;
            });
            
            pwaBtn.addEventListener('click', async () => {
                if (!deferredPrompt) {
                    alert("আপনার ব্রাউজারে অ্যাপটি ইতিমধ্যে ইনস্টল করা আছে অথবা আপনার ব্রাউজার এই মুহূর্তে ইনস্টল করা সমর্থন করছে না। অনুগ্রহ করে ক্রোম (Chrome), এজ (Edge) অথবা এন্ড্রয়েড ক্রোম ব্যবহার করুন!");
                    return;
                }
                deferredPrompt.prompt();
                const { outcome } = await deferredPrompt.userChoice;
                console.log(`User installation choice outcome: \${outcome}`);
                deferredPrompt = null;
            });

            // Register Service Worker for PWA compliance
            if ('serviceWorker' in navigator) {
                window.addEventListener('load', () => {
                    navigator.serviceWorker.register('/sw.js')
                        .then(reg => {
                            console.log('Sovereign PWA Service Worker successfully registered!', reg.scope);
                        })
                        .catch(err => {
                            console.error('Service Worker registration error:', err);
                        });
                });
            }

            // Bind loops
            connectWS();
            setTimeout(fetchMotionState, 150); // Optional fallback/initial fetch
            setTimeout(initMotionConfig, 250);
            setTimeout(initStreamConfig, 500);
            setInterval(initStreamConfig, 4000);
        </script>
    </body>
    </html>
  `);
});

// Proxy/Route: Start MJPEG Stream Relay
app.get('/api/stream/start', (req, res) => {
  isStreamRunning = true;
  log("Stream relay activated by user action.");
  res.json({ success: true, active: true });
});

// Proxy/Route: Stop MJPEG Stream Relay
app.get('/api/stream/stop', (req, res) => {
  isStreamRunning = false;
  log("Stream relay suspended by user action. Terminating all active relays.");
  for (const abortController of activeRelays) {
    try {
      abortController.abort();
    } catch (err) {
      // Ignore
    }
  }
  activeRelays.clear();
  res.json({ success: true, active: false });
});

// Proxy/Route: MJPEG Live Video Stream Relay
app.get('/live', async (req, res) => {
  if (!isStreamRunning) {
    log("Rejected stream request: live relay stopped.");
    res.status(503).send("Stream relay is suspended. Enable it from the Sovereign Router Console.");
    return;
  }

  // Set response headers standard to Motion-JPEG streams
  res.writeHead(200, {
    'Content-Type': 'multipart/x-mixed-replace; boundary=--frame',
    'Cache-Control': 'no-cache, no-store, must-revalidate',
    'Connection': 'keep-alive',
    'Pragma': 'no-cache',
    'Expires': '0'
  });

  const abortController = new AbortController();
  activeRelays.add(abortController);
  liveClients.add(res);

  // Stop upstream connection if routing client disconnects
  req.on('close', () => {
    log("Client stream request aborted. Cleaning routed connections...");
    abortController.abort();
    activeRelays.delete(abortController);
    liveClients.delete(res);
  });

  const liveStreamUrl = getTargetUrl('/live');
  log(`Establishing live-stream relay route to Android Camera: ${liveStreamUrl}`);

  try {
    const upstreamResponse = await axios({
      method: 'GET',
      url: liveStreamUrl,
      responseType: 'stream',
      signal: abortController.signal,
      timeout: 3000 // Shortened handshake limit to fall back to Cloud-Push mode quickly
    });

    // Stream/pipe content data from device back through our express gateway
    upstreamResponse.data.pipe(res);

  } catch (error) {
    if (axios.isCancel(error)) {
      log("Upstream stream relay successfully closed.");
    } else {
      log(`Local relay unavailable (${error.message}). Running in cloud-push listener mode.`);
      // Do NOT end response; keeping stream open so POST /api/stream/push can write directly
    }
  }
});

// Cloud-Push Route: Android Device Pushing Frames to Cloud Server
app.post('/api/stream/push', express.raw({ type: 'image/jpeg', limit: '15mb' }), (req, res) => {
  const battery = parseInt(req.headers['x-battery-level'] || '100');
  const fps = parseInt(req.headers['x-fps'] || '0');
  const camera = req.headers['x-camera-type'] || 'BACK';

  // Sync global stats with pushed headers
  currentStats.batteryLevel = battery;
  currentStats.fps = fps;
  currentStats.cameraType = camera;

  const jpegFrame = req.body;
  if (jpegFrame && jpegFrame.length > 0) {
    // 1. Process local motion detector on the received jpeg frame buffer
    const now = Date.now();
    if (now - lastAnalysisTime >= motionConfig.samplingInterval) {
      lastAnalysisTime = now;
      try {
        analyzeFrame(jpegFrame);
      } catch (err) {
        // Ignore partial/bad decodes
      }
    }

    // 2. Broadcast/write this frame in real time to all open /live viewers
    const header = `--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegFrame.length}\r\n\r\n`;
    liveClients.forEach(client => {
      try {
        client.write(Buffer.concat([Buffer.from(header), jpegFrame, Buffer.from('\r\n')]));
      } catch (e) {
        liveClients.delete(client);
      }
    });
  }

  // Respond with all pending control actions/commands queued up in cloud web dashboard
  res.json({
    success: true,
    commands: pendingCommands
  });
  pendingCommands = []; // Clear queue after acknowledging
});

// Proxy/Route: Device Control Flashlight
app.get('/api/toggle-flash', async (req, res) => {
  const target = getTargetUrl('/api/toggle-flash');
  log(`Forwarding Flash Toggle Request to: ${target}`);
  
  // Always queue for remote cloud push
  pendingCommands.push("toggle_flash");

  try {
    const response = await axios.get(target, { timeout: 1500 });
    res.json(response.data);
  } catch (error) {
    log(`Flash action queued in cloud queue: ${error.message}`);
    res.json({ success: true, mode: 'cloud_queued' });
  }
});

// Proxy/Route: Device Control Camera Switching
app.get('/api/switch-camera', async (req, res) => {
  const target = getTargetUrl('/api/switch-camera');
  log(`Forwarding Flip-Camera Request to: ${target}`);
  
  // Always queue for remote cloud push
  pendingCommands.push("switch_camera");

  try {
    const response = await axios.get(target, { timeout: 1500 });
    res.json(response.data);
  } catch (error) {
    log(`Flip-Camera action queued in cloud queue: ${error.message}`);
    res.json({ success: true, mode: 'cloud_queued' });
  }
});

// Proxy/Route: Get/Set Device Stream Configuration (Resolution, FPS)
app.get('/api/stream/config', async (req, res) => {
  try {
    const target = getTargetUrl('/api/config');
    log(`Fetching camera config: ${target}`);
    const response = await axios.get(target, { timeout: 1500 });
    res.json(response.data);
  } catch (error) {
    // In cloud mode, return current configuration
    res.json({
      success: true,
      resolution: currentStats.cameraType, // approximate
      targetFps: currentStats.fps
    });
  }
});

app.post('/api/stream/config', async (req, res) => {
  const { resolution, targetFps } = req.body;
  
  if (resolution) {
    pendingCommands.push(`set_resolution_${resolution}`);
  }
  if (targetFps) {
    pendingCommands.push(`set_fps_${targetFps}`);
  }

  try {
    let query = [];
    if (resolution) query.push(`resolution=${resolution}`);
    if (targetFps) query.push(`fps=${targetFps}`);
    const queryString = query.length > 0 ? '?' + query.join('&') : '';
    
    const target = getTargetUrl(`/api/config${queryString}`);
    log(`Configuring target device: ${target}`);
    const response = await axios.get(target, { timeout: 1500 });
    res.json(response.data);
  } catch (error) {
    log(`Config set queued in cloud queue: ${error.message}`);
    res.json({ success: true, mode: 'cloud_queued' });
  }
});

// Proxy/Route: Device Diagnostics & Stats
app.get('/api/stats', async (req, res) => {
  try {
    const target = getTargetUrl('/api/stats');
    const response = await axios.get(target, { timeout: 2000 });
    
    currentStats = {
      ...response.data,
      activeRelays: activeRelays.size,
      isStreamRunning: isStreamRunning
    };
    res.json(currentStats);
  } catch (error) {
    // Return cached stats + active relay count if webcam device is temporarily down
    res.json({
      ...currentStats,
      activeRelays: activeRelays.size,
      connected: false,
      isStreamRunning: isStreamRunning
    });
  }
});

// Motion Detection APIs
app.get('/api/motion/config', (req, res) => {
  res.json(motionConfig);
});

app.post('/api/motion/config', (req, res) => {
  const { enabled, sensitivity, threshold, stride, samplingInterval } = req.body;
  
  if (typeof enabled !== 'undefined') motionConfig.enabled = !!enabled;
  if (typeof sensitivity !== 'undefined') motionConfig.sensitivity = parseFloat(sensitivity);
  if (typeof threshold !== 'undefined') motionConfig.threshold = parseInt(threshold);
  if (typeof stride !== 'undefined') motionConfig.stride = parseInt(stride);
  if (typeof samplingInterval !== 'undefined') motionConfig.samplingInterval = parseInt(samplingInterval);
  
  log(`[Motion] Configuration updated: ${JSON.stringify(motionConfig)}`);
  
  if (motionConfig.enabled && !motionStreamActive) {
    startBackgroundMotionDetection();
  }
  
  res.json({ success: true, config: motionConfig });
});

app.get('/api/motion/status', (req, res) => {
  res.json({
    enabled: motionConfig.enabled,
    isMotionDetected: motionState.isMotionDetected,
    currentIntensity: motionState.currentIntensity,
    recentEvents: motionState.recentEvents.slice(0, 15) // Output top 15 events
  });
});

app.delete('/api/motion/events', (req, res) => {
  motionState.recentEvents = [];
  log("[Motion] Academic logs in router memory cleared.");
  broadcast({
    type: 'intensity_update',
    data: {
      intensity: motionState.currentIntensity,
      isMotionDetected: motionState.isMotionDetected,
      recentEvents: []
    }
  });
  res.json({ success: true });
});

// Server-Side Motion Estimation Loop and Pixel Arithmetic
function startBackgroundMotionDetection() {
  if (motionStreamActive) return;
  motionStreamActive = true;
  runMotionLoop();
}

async function runMotionLoop() {
  log("[Motion] Starting background pixel-differencing engine...");
  while (motionStreamActive) {
    if (!motionConfig.enabled) {
      motionState.currentIntensity = 0;
      motionState.isMotionDetected = false;
      await new Promise(resolve => setTimeout(resolve, 800));
      continue;
    }

    try {
      const streamUrl = getTargetUrl('/live');
      log(`[Motion] Attaching differencing observer to stream source: ${streamUrl}`);
      
      const response = await axios({
        method: 'GET',
        url: streamUrl,
        responseType: 'stream',
        timeout: 10000
      });
      
      let accumulator = Buffer.alloc(0);
      
      await new Promise((resolve, reject) => {
        const stream = response.data;
        
        stream.on('data', (chunk) => {
          if (!motionConfig.enabled) {
            stream.destroy();
            reject(new Error("Motion scanning disabled"));
            return;
          }
          
          accumulator = Buffer.concat([accumulator, chunk]);
          
          while (true) {
            const startIndex = accumulator.indexOf(Buffer.from([0xFF, 0xD8]));
            if (startIndex === -1) {
              if (accumulator.length > 0) {
                accumulator = accumulator.subarray(accumulator.length - 1);
              }
              break;
            }
            
            const endIndex = accumulator.indexOf(Buffer.from([0xFF, 0xD9]), startIndex + 2);
            if (endIndex === -1) {
              if (startIndex > 0) {
                accumulator = accumulator.subarray(startIndex);
              }
              break;
            }
            
            const jpegFrame = accumulator.subarray(startIndex, endIndex + 2);
            accumulator = accumulator.subarray(endIndex + 2);
            
            const now = Date.now();
            if (now - lastAnalysisTime >= motionConfig.samplingInterval) {
              lastAnalysisTime = now;
              try {
                analyzeFrame(jpegFrame);
              } catch (err) {
                // Ignore decoding errors from partially buffered frames
              }
            }
          }
        });
        
        stream.on('end', () => resolve());
        stream.on('error', (err) => reject(err));
      });
      
    } catch (err) {
      log(`[Motion] Stream collector observer interrupted: ${err.message}. Reconnecting in 5s...`);
      motionState.currentIntensity = 0;
      if (motionState.isMotionDetected) {
        motionState.isMotionDetected = false;
        const currentEvent = motionState.recentEvents[0];
        if (currentEvent && currentEvent.active) {
          currentEvent.active = false;
          currentEvent.endTime = new Date().toISOString();
        }
      }
      await new Promise(resolve => setTimeout(resolve, 5000));
    }
  }
}

function analyzeFrame(jpegBuffer) {
  const rawImage = jpeg.decode(jpegBuffer, { useTArray: true });
  const { width, height, data: currentData } = rawImage;
  
  if (!lastFrameBuffer || lastFrameBuffer.width !== width || lastFrameBuffer.height !== height) {
    lastFrameBuffer = {
      width,
      height,
      data: Buffer.from(currentData)
    };
    return;
  }
  
  const prevData = lastFrameBuffer.data;
  const stride = motionConfig.stride;
  const threshold = motionConfig.threshold;
  
  let changedCount = 0;
  let totalChecked = 0;
  
  // Stride-based pixel scanning for performance optimization
  for (let y = 0; y < height; y += stride) {
    for (let x = 0; x < width; x += stride) {
      const idx = (y * width + x) * 4;
      
      const rDiff = Math.abs(currentData[idx] - prevData[idx]);
      const gDiff = Math.abs(currentData[idx + 1] - prevData[idx + 1]);
      const bDiff = Math.abs(currentData[idx + 2] - prevData[idx + 2]);
      
      const intensityDiff = (rDiff + gDiff + bDiff) / 3;
      if (intensityDiff > threshold) {
        changedCount++;
      }
      totalChecked++;
    }
  }
  
  const intensity = (changedCount / totalChecked) * 100;
  motionState.currentIntensity = Math.round(intensity * 10) / 10;
  
  const isCurrentlyTriggered = intensity >= motionConfig.sensitivity;
  const now = Date.now();
  
  if (isCurrentlyTriggered) {
    if (!motionState.isMotionDetected) {
      // Transition: Motion Started!
      motionState.isMotionDetected = true;
      motionState.lastEventTime = now;
      
      const newEvent = {
        id: Math.random().toString(36).substring(2, 9),
        startTime: new Date().toISOString(),
        endTime: null,
        maxIntensity: motionState.currentIntensity,
        durationSecs: 0,
        active: true
      };
      
      motionState.recentEvents.unshift(newEvent);
      if (motionState.recentEvents.length > 50) {
        motionState.recentEvents.pop();
      }
      log(`[Motion Detected] Live motion event registered! Intensity: ${motionState.currentIntensity}%`);
    } else {
      // Continuing: Update current live event duration and peak intensity
      const activeEvent = motionState.recentEvents[0];
      if (activeEvent && activeEvent.active) {
        if (motionState.currentIntensity > activeEvent.maxIntensity) {
          activeEvent.maxIntensity = motionState.currentIntensity;
        }
        activeEvent.durationSecs = Math.round((now - new Date(activeEvent.startTime).getTime()) / 1000);
      }
    }
  } else {
    if (motionState.isMotionDetected) {
      // Transition: Motion Stopped!
      motionState.isMotionDetected = false;
      const activeEvent = motionState.recentEvents[0];
      if (activeEvent && activeEvent.active) {
        activeEvent.active = false;
        activeEvent.endTime = new Date().toISOString();
        activeEvent.durationSecs = Math.round((now - new Date(activeEvent.startTime).getTime()) / 1000);
        log(`[Motion Cleared] Live motion activity ended. Peak: ${activeEvent.maxIntensity}%, Duration: ${activeEvent.durationSecs}s`);
      }
    }
  }
  
  // Real-time WebSocket Status Broadcast
  broadcast({
    type: 'intensity_update',
    data: {
      intensity: motionState.currentIntensity,
      isMotionDetected: motionState.isMotionDetected,
      recentEvents: motionState.recentEvents.slice(0, 15)
    }
  });

  // High speed direct buffer assignment of current state
  lastFrameBuffer.data = Buffer.from(currentData);
}

// Progressive Web App (PWA) Manifest setup
app.get('/manifest.json', (req, res) => {
  res.json({
    "name": "Sovereign Web Stream",
    "short_name": "Sovereign Web",
    "description": "STANDALONE IP CAMERA TRANSMITTER AND MOTION DETECTOR CLIENT",
    "start_url": "/",
    "display": "standalone",
    "background_color": "#0d1117",
    "theme_color": "#00ADB5",
    "orientation": "portrait",
    "icons": [
      {
        "src": "/logo.svg",
        "sizes": "192x192 512x512",
        "type": "image/svg+xml",
        "purpose": "any maskable"
      }
    ]
  });
});

// PWA Offline-compliant Service Worker definition
app.get('/sw.js', (req, res) => {
  res.setHeader('Content-Type', 'application/javascript');
  res.send(`
    const CACHE_NAME = 'sovereign-stream-v3';
    const ASSETS = [
      '/',
      '/manifest.json',
      '/logo.svg',
      'https://cdn.tailwindcss.com',
      'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css'
    ];

    self.addEventListener('install', (e) => {
      e.waitUntil(
        caches.open(CACHE_NAME).then((cache) => {
          return cache.addAll(ASSETS).catch(() => {});
        })
      );
    });

    self.addEventListener('fetch', (e) => {
      e.respondWith(
        caches.match(e.request).then((cachedResponse) => {
          return cachedResponse || fetch(e.request).catch(() => caches.match('/'));
        })
      );
    });
  `);
});

// High-fidelity security webcam lens SVG icon
app.get('/logo.svg', (req, res) => {
  res.setHeader('Content-Type', 'image/svg+xml');
  res.send(`
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="512" height="512">
      <circle cx="50" cy="50" r="48" fill="#090d1a" stroke="#00adb5" stroke-width="2.5" />
      <circle cx="50" cy="50" r="38" fill="none" stroke="#1f2d4d" stroke-dasharray="6,4" stroke-width="2" />
      <circle cx="50" cy="50" r="30" fill="none" stroke="#222831" stroke-width="3" />
      <circle cx="50" cy="50" r="22" fill="none" stroke="#00adb5" stroke-width="1.5" stroke-dasharray="25,5,15,5" />
      <circle cx="50" cy="50" r="14" fill="#111" />
      <path d="M 50,30 A 20,20 0 0,1 70,50" fill="none" stroke="#ffffff" stroke-width="2" stroke-linecap="round" opacity="0.4" />
      <circle cx="50" cy="50" r="10" fill="#032a30" />
      <circle cx="50" cy="50" r="6" fill="#00e6ff" />
      <circle cx="48" cy="48" r="1.5" fill="#ffffff" />
      <circle cx="50" cy="50" r="42" fill="none" stroke="#00adb5" stroke-width="0.5" opacity="0.3" />
    </svg>
  `);
});

// Android Camera Transceiver APK direct download route
app.get('/download-apk', (req, res) => {
  const fs = require('fs');
  const path = require('path');
  
  // High-priority resolution list of potential compiled build locations
  const apkPaths = [
    '/app/build/outputs/apk/debug/app-debug.apk',
    path.join(__dirname, '../app/build/outputs/apk/debug/app-debug.apk'),
    '/app/build/outputs/apk/release/app-release-unsigned.apk',
    path.join(__dirname, '../app/build/outputs/apk/release/app-release-unsigned.apk'),
  ];
  
  let served = false;
  for (const apkPath of apkPaths) {
    if (fs.existsSync(apkPath)) {
      log(`Serving local Android APK built package: \${apkPath}`);
      res.download(apkPath, 'Sovereign_Webcam_Transmitter.apk');
      served = true;
      break;
    }
  }
  
  if (!served) {
    log(`Warning: Android APK file not found at prospective build outputs.`);
    res.status(404).send(\`
      <!DOCTYPE html>
      <html lang="bn">
      <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>APK খুঁজে পাওয়া যায়নি | Sovereign</title>
          <script src="https://cdn.tailwindcss.com"></script>
      </head>
      <body class="bg-[#0d1117] text-zinc-100 min-h-screen flex items-center justify-center p-4">
          <div class="max-w-md w-full bg-zinc-950 border border-red-500/30 rounded-2xl p-8 text-center space-y-6 shadow-2xl">
              <div class="w-16 h-16 bg-red-500/10 text-red-500 rounded-full flex items-center justify-center mx-auto border border-red-500/20">
                  <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-8 h-8">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
                  </svg>
              </div>
              <div class="space-y-2">
                  <h2 class="text-xl font-bold text-red-500">⚠ APK ফাইলটি তৈরি হয়নি!</h2>
                  <p class="text-zinc-400 text-sm leading-relaxed">এন্ড্রয়েড স্টুডিও সাইডবার অথবা সার্ভার বিল্ড প্রক্রিয়া চলমান থাকায় ফাইলটি এখনও তৈরি করা হচ্ছে বা কম্পাইল করা হয়নি।</p>
                  <p class="text-zinc-500 text-xs text-zinc-500">অনুগ্রহ করে AI Studio-র <strong>compile_applet</strong> বা আপনার এন্ড্রয়েড প্রজেক্টের বিল্ড প্রসেস সফলভাবে সম্পন্ন করুন, যাতে APK ফাইলটি তৈরি হয় এবং এরপর পুনরায় ডাউনলোডের চেষ্টা করুন।</p>
              </div>
              <div class="pt-4 flex justify-center space-x-3">
                  <a href="/" class="bg-zinc-800 hover:bg-zinc-700 text-zinc-300 font-bold py-2 px-6 rounded-xl text-xs transition duration-200">
                      মেনু ড্যাশবোর্ড
                  </a>
                  <button onclick="window.location.reload()" class="bg-teal-500 hover:bg-teal-400 text-black font-extrabold py-2 px-6 rounded-xl text-xs transition duration-200">
                      পুনরায় চেষ্টা করুন
                  </button>
              </div>
          </div>
      </body>
      </html>
    \`);
  }
});

// Start listening
server.listen(PORT, () => {
  console.log(`=============================================================`);
  console.log(`  Sovereign Live MJPEG Stream Router Engine Online           `);
  console.log(`  Listening locally on: http://localhost:${PORT}             `);
  console.log(`  Broadcasting device target: ${WEBCAM_URL}                  `);
  console.log(`=============================================================`);
  
  // Automate motion analyzer start
  startBackgroundMotionDetection();
});
