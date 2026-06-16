# Sovereign IP Webcam - Companion MJPEG Express Router

This Node.js server routes, proxies, and multi-casts live MJPEG video streams and device controls directly from your Android Sovereign IP Webcam application. It acts as a dedicated relay/gateway proxy to:

1. **Proxy streaming feeds** without draining the mobile device's CPU/battery with duplicate multi-connections.
2. **Expose dynamic controls** so third-party systems like OBS, Home Assistant, Node-RED, or VLC can trigger flashlight, toggle front/back camera orientations, or pull live telemetry diagnostics seamlessly.
3. **Serve a gorgeous dark web interface** representing live fps, active relays, battery percentage, active viewer counts, and responsive stream viewer consoles.

---

## Technical Prerequisites

Ensure you have [Node.js](https://nodejs.org) (v18.0.0 or higher) installed on your host system.

---

## Installation & Configuration

1. **Copy the server folder** to your computer or execute direct commands from inside this `/server` directory:

```bash
cd server
npm install
```

2. **Configure environment variables** (optional):
   - `PORT`: The port where the router server will run (Default is `3000`).
   - `WEBCAM_URL`: The streaming URL served by your Android device (Default is `http://192.168.1.XXX:8080`).

---

## Launching the Router

Start the router engine:

```bash
# Using default options (routes webcam on localhost:8080)
npm start

# Or custom port and custom webcam IP address:
PORT=4000 WEBCAM_URL=http://192.168.1.45:8080 npm start
```

---

## Proxied Router API Endpoints

Once powered up, use these routed paths on your companion router:

- **Beautiful Visual Web Console**: `http://localhost:3000/`
- **Routed MJPEG Live Video Feed**: `http://localhost:3000/live`
- **Remote Flashlight Toggle Proxy**: `http://localhost:3000/api/toggle-flash`
- **Remote Camera Flip Orientation Proxy**: `http://localhost:3000/api/switch-camera`
- **Unified Telemetry Metrics & Relays**: `http://localhost:3000/api/stats`
