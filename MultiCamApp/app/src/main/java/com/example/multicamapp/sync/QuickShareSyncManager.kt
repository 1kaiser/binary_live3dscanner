package com.example.multicamapp.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.*
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class SyncRole {
    STANDALONE,
    HOST,
    WORKER
}

data class SyncNode(
    val endpointId: String,
    val name: String,
    val isConnected: Boolean = true,
    val clockOffsetMs: Long = 0L,
    val lastPingMs: Long = System.currentTimeMillis(),
    val concurrencyMode: String = "",
    val activeCameras: List<String> = emptyList(),
    val availableCameras: List<String> = emptyList()
)

class QuickShareSyncManager(private val context: Context) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _role = MutableStateFlow(SyncRole.STANDALONE)
    val role: StateFlow<SyncRole> = _role.asStateFlow()

    private val _connectedNodes = MutableStateFlow<List<SyncNode>>(emptyList())
    val connectedNodes: StateFlow<List<SyncNode>> = _connectedNodes.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _statusText = MutableStateFlow("Quick Share: Standalone")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    val localIpAddress: String? get() = findLocalIpAddress()

    private val activeNodesMap = ConcurrentHashMap<String, SyncNode>()
    private val activeTcpClients = ConcurrentHashMap<String, Socket>()
    private var hostEndpointId: String? = null
    private var workerTcpSocket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var estimatedClockOffsetMs = 0L

    // Callbacks for local hardware execution
    var onSyncPhotoTrigger: ((sessionId: String) -> Unit)? = null
    var onSyncRecordStartTrigger: ((sessionId: String) -> Unit)? = null
    var onSyncRecordStopTrigger: (() -> Unit)? = null

    val localDeviceName: String = run {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        "$manufacturer $model"
    }

    // --- Google Nearby Connections Callbacks ---

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val jsonStr = String(bytes, StandardCharsets.UTF_8)
                handleIncomingMessage(endpointId, jsonStr)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Nearby Connection initiated from: ${info.endpointName} ($endpointId)")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    val node = SyncNode(endpointId, info.endpointName)
                    activeNodesMap[endpointId] = node
                    updateNodesList()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to accept connection: $endpointId", e)
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d(TAG, "Connection confirmed with $endpointId")
                if (_role.value == SyncRole.WORKER) {
                    hostEndpointId = endpointId
                    _statusText.value = "Connected to Host via Quick Share"
                    _isSearching.value = false
                    sendPing(endpointId)
                } else if (_role.value == SyncRole.HOST) {
                    _statusText.value = "Host: ${activeNodesMap.size} node(s) connected"
                }
                updateNodesList()
            } else {
                Log.w(TAG, "Connection failed: $endpointId status: ${result.status}")
                activeNodesMap.remove(endpointId)
                updateNodesList()
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            activeNodesMap.remove(endpointId)
            if (endpointId == hostEndpointId) {
                hostEndpointId = null
                _statusText.value = "Host disconnected"
            } else if (_role.value == SyncRole.HOST) {
                _statusText.value = "Host: ${activeNodesMap.size} node(s) connected"
            }
            updateNodesList()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Nearby discovered: ${info.endpointName} ($endpointId)")
            _statusText.value = "Pairing with ${info.endpointName}..."
            connectionsClient.requestConnection(localDeviceName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Request connection failed", e)
                }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost endpoint: $endpointId")
        }
    }

    // --- Role Management ---

    fun startHost() {
        stopSync()
        _role.value = SyncRole.HOST
        val ip = localIpAddress ?: "Wi-Fi"
        _statusText.value = "Host: $ip (Waiting for nodes...)"
        _isSearching.value = true

        // 1. Google Nearby Connections Advertising
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        connectionsClient.startAdvertising(
            "$localDeviceName (Host)",
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Host Quick Share advertising started")
        }.addOnFailureListener { e ->
            Log.w(TAG, "Quick Share advertising warning: ${e.message}")
        }

        // 2. High-speed Direct TCP ServerSocket
        startTcpServer()

        // 3. UDP Discovery Broadcast on LAN
        startUdpBeaconSender()
    }

    fun startWorker(targetHostIp: String? = null) {
        stopSync()
        _role.value = SyncRole.WORKER
        _statusText.value = "Scanning for Host..."
        _isSearching.value = true

        // 1. Google Nearby Discovery
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Worker Quick Share discovery started")
        }.addOnFailureListener { e ->
            Log.w(TAG, "Quick Share discovery warning: ${e.message}")
        }

        // 2. UDP Beacon Receiver
        startUdpBeaconReceiver()

        // 3. Direct IP connection if provided
        if (!targetHostIp.isNullOrBlank()) {
            connectToHostIp(targetHostIp.trim())
        }
    }

    fun connectToHostIp(ip: String) {
        syncScope.launch {
            try {
                _statusText.value = "Connecting to $ip:$TCP_PORT..."
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, TCP_PORT), 4000)
                workerTcpSocket = socket
                val endpointId = "tcp_$ip"
                hostEndpointId = endpointId
                val node = SyncNode(endpointId, "Host ($ip)")
                activeNodesMap[endpointId] = node
                _isSearching.value = false
                _statusText.value = "Connected to Host ($ip)"
                updateNodesList()

                // Start reader & initial ping
                startSocketReader(socket, endpointId)
                sendPing(endpointId)
            } catch (e: Exception) {
                Log.e(TAG, "Direct TCP connection to $ip failed", e)
                _statusText.value = "Connection to $ip failed: ${e.message}"
            }
        }
    }

    fun stopSync() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (ignored: Exception) {}

        try {
            serverSocket?.close()
            serverSocket = null
        } catch (ignored: Exception) {}

        try {
            workerTcpSocket?.close()
            workerTcpSocket = null
        } catch (ignored: Exception) {}

        activeTcpClients.values.forEach {
            try { it.close() } catch (ignored: Exception) {}
        }
        activeTcpClients.clear()
        activeNodesMap.clear()

        hostEndpointId = null
        estimatedClockOffsetMs = 0L
        _role.value = SyncRole.STANDALONE
        _isSearching.value = false
        _statusText.value = "Quick Share: Standalone"
        updateNodesList()
    }

    private fun updateNodesList() {
        mainHandler.post {
            _connectedNodes.value = activeNodesMap.values.toList()
        }
    }

    // --- Direct TCP / UDP Networking Engine ---

    private fun startTcpServer() {
        syncScope.launch {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(TCP_PORT))
                serverSocket = server
                while (_role.value == SyncRole.HOST && !server.isClosed) {
                    val client = server.accept()
                    val clientIp = client.inetAddress.hostAddress ?: "client"
                    val endpointId = "tcp_$clientIp"
                    activeTcpClients[endpointId] = client
                    val node = SyncNode(endpointId, "Worker ($clientIp)")
                    activeNodesMap[endpointId] = node
                    _statusText.value = "Host: ${activeNodesMap.size} node(s) connected"
                    updateNodesList()
                    startSocketReader(client, endpointId)
                }
            } catch (e: Exception) {
                if (_role.value == SyncRole.HOST) {
                    Log.e(TAG, "TCP Server exception", e)
                }
            }
        }
    }

    private fun startSocketReader(socket: Socket, endpointId: String) {
        syncScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    handleIncomingMessage(endpointId, line)
                }
            } catch (ignored: Exception) {
            } finally {
                activeTcpClients.remove(endpointId)
                activeNodesMap.remove(endpointId)
                updateNodesList()
            }
        }
    }

    private fun startUdpBeaconSender() {
        syncScope.launch {
            val ip = localIpAddress ?: return@launch
            val beaconMsg = JSONObject().apply {
                put("type", "MULTICAM_BEACON")
                put("host", localDeviceName)
                put("ip", ip)
                put("port", TCP_PORT)
            }.toString().toByteArray(StandardCharsets.UTF_8)

            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(beaconMsg, beaconMsg.size, broadcastAddr, UDP_PORT)

                while (_role.value == SyncRole.HOST) {
                    socket.send(packet)
                    delay(1500)
                }
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "UDP Beacon sender error", e)
            }
        }
    }

    private fun startUdpBeaconReceiver() {
        syncScope.launch {
            try {
                val socket = DatagramSocket(UDP_PORT)
                socket.soTimeout = 2000
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (_role.value == SyncRole.WORKER && workerTcpSocket == null) {
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                        val json = JSONObject(msg)
                        if (json.optString("type") == "MULTICAM_BEACON") {
                            val hostIp = json.getString("ip")
                            Log.d(TAG, "Discovered Host via UDP beacon: $hostIp")
                            connectToHostIp(hostIp)
                            break
                        }
                    } catch (ignored: SocketTimeoutException) {
                    }
                }
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "UDP Beacon receiver error", e)
            }
        }
    }

    // --- Message Handling & Time Synchronization ---

    private fun handleIncomingMessage(endpointId: String, jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            when (json.optString("action")) {
                "PING" -> {
                    val t0 = json.getLong("t0")
                    val reply = JSONObject().apply {
                        put("action", "PONG")
                        put("t0", t0)
                        put("tServer", System.currentTimeMillis())
                    }
                    sendPayload(endpointId, reply.toString())
                }
                "PONG" -> {
                    val t0 = json.getLong("t0")
                    val tServer = json.getLong("tServer")
                    val t1 = System.currentTimeMillis()
                    val rtt = t1 - t0
                    estimatedClockOffsetMs = tServer - (t0 + rtt / 2)
                    Log.d(TAG, "Synced clock with host. Offset: ${estimatedClockOffsetMs}ms, RTT: ${rtt}ms")
                    val node = activeNodesMap[endpointId]?.copy(clockOffsetMs = estimatedClockOffsetMs)
                    if (node != null) {
                        activeNodesMap[endpointId] = node
                        updateNodesList()
                    }
                }
                "CAPTURE_PHOTO" -> {
                    val targetTime = json.getLong("targetTime")
                    val sessionId = json.getString("sessionId")
                    scheduleExecution(targetTime) {
                        Log.d(TAG, "Worker executing synchronized photo click: $sessionId")
                        onSyncPhotoTrigger?.invoke(sessionId)
                    }
                }
                "START_RECORDING" -> {
                    val targetTime = json.getLong("targetTime")
                    val sessionId = json.getString("sessionId")
                    scheduleExecution(targetTime) {
                        Log.d(TAG, "Worker executing synchronized video start: $sessionId")
                        onSyncRecordStartTrigger?.invoke(sessionId)
                    }
                }
                "STOP_RECORDING" -> {
                    mainHandler.post {
                        Log.d(TAG, "Worker executing synchronized video stop")
                        onSyncRecordStopTrigger?.invoke()
                    }
                }
                "DEVICE_STATUS" -> {
                    val mode = json.optString("concurrencyMode", "")
                    val actList = mutableListOf<String>()
                    val actArr = json.optJSONArray("activeCameras")
                    if (actArr != null) {
                        for (i in 0 until actArr.length()) actList.add(actArr.getString(i))
                    }
                    val avList = mutableListOf<String>()
                    val avArr = json.optJSONArray("availableCameras")
                    if (avArr != null) {
                        for (i in 0 until avArr.length()) avList.add(avArr.getString(i))
                    }
                    val existing = activeNodesMap[endpointId]
                    if (existing != null) {
                        activeNodesMap[endpointId] = existing.copy(
                            concurrencyMode = mode,
                            activeCameras = actList,
                            availableCameras = avList
                        )
                        updateNodesList()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming sync message", e)
        }
    }

    fun broadcastDeviceStatus(
        concurrencyMode: String,
        activeCameras: List<String>,
        availableCameras: List<String>
    ) {
        val payloadObj = JSONObject().apply {
            put("action", "DEVICE_STATUS")
            put("deviceName", localDeviceName)
            put("concurrencyMode", concurrencyMode)
            val actArr = org.json.JSONArray()
            activeCameras.forEach { actArr.put(it) }
            put("activeCameras", actArr)
            val avArr = org.json.JSONArray()
            availableCameras.forEach { avArr.put(it) }
            put("availableCameras", avArr)
        }
        val msg = payloadObj.toString()
        for (endpointId in activeNodesMap.keys) {
            sendPayload(endpointId, msg)
        }
    }

    private fun sendPing(endpointId: String) {
        val ping = JSONObject().apply {
            put("action", "PING")
            put("t0", System.currentTimeMillis())
        }
        sendPayload(endpointId, ping.toString())
    }

    private fun scheduleExecution(targetHostTimeMs: Long, action: () -> Unit) {
        val localTargetTime = targetHostTimeMs - estimatedClockOffsetMs
        val now = System.currentTimeMillis()
        val delayMs = (localTargetTime - now).coerceAtLeast(0)

        mainHandler.postDelayed({
            action()
        }, delayMs)
    }

    private fun sendPayload(endpointId: String, message: String) {
        syncScope.launch {
            if (endpointId.startsWith("tcp_")) {
                val socket = if (_role.value == SyncRole.HOST) activeTcpClients[endpointId] else workerTcpSocket
                try {
                    socket?.let {
                        val writer = PrintWriter(it.getOutputStream(), true)
                        writer.println(message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TCP send error to $endpointId", e)
                }
            } else {
                val bytes = message.toByteArray(StandardCharsets.UTF_8)
                val payload = Payload.fromBytes(bytes)
                connectionsClient.sendPayload(endpointId, payload)
            }
        }
    }

    private fun broadcastToAllNodes(message: String) {
        syncScope.launch {
            // 1. Send to all TCP clients
            activeTcpClients.values.forEach { socket ->
                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(message)
                } catch (ignored: Exception) {}
            }

            // 2. Send to all Nearby endpoints
            val nearbyEndpoints = activeNodesMap.keys.filterNot { it.startsWith("tcp_") }
            if (nearbyEndpoints.isNotEmpty()) {
                val bytes = message.toByteArray(StandardCharsets.UTF_8)
                val payload = Payload.fromBytes(bytes)
                connectionsClient.sendPayload(nearbyEndpoints, payload)
            }
        }
    }

    // --- Public Synchronized Triggers ---

    fun triggerSynchronousPhoto(onLocalTrigger: (sessionId: String) -> Unit) {
        val sessionId = "SESSION_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        if (_role.value == SyncRole.HOST && activeNodesMap.isNotEmpty()) {
            val targetTime = System.currentTimeMillis() + SYNC_PHOTO_LEAD_TIME_MS
            val msg = JSONObject().apply {
                put("action", "CAPTURE_PHOTO")
                put("targetTime", targetTime)
                put("sessionId", sessionId)
            }
            broadcastToAllNodes(msg.toString())
            scheduleExecution(targetTime) {
                onLocalTrigger(sessionId)
            }
        } else {
            onLocalTrigger(sessionId)
        }
    }

    fun triggerSynchronousRecordStart(onLocalTrigger: (sessionId: String) -> Unit) {
        val sessionId = "REC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        if (_role.value == SyncRole.HOST && activeNodesMap.isNotEmpty()) {
            val targetTime = System.currentTimeMillis() + SYNC_RECORD_LEAD_TIME_MS
            val msg = JSONObject().apply {
                put("action", "START_RECORDING")
                put("targetTime", targetTime)
                put("sessionId", sessionId)
            }
            broadcastToAllNodes(msg.toString())
            scheduleExecution(targetTime) {
                onLocalTrigger(sessionId)
            }
        } else {
            onLocalTrigger(sessionId)
        }
    }

    fun triggerSynchronousRecordStop(onLocalTrigger: () -> Unit) {
        if (_role.value == SyncRole.HOST && activeNodesMap.isNotEmpty()) {
            val msg = JSONObject().apply {
                put("action", "STOP_RECORDING")
            }
            broadcastToAllNodes(msg.toString())
        }
        onLocalTrigger()
    }

    private fun findLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    fun onDestroy() {
        stopSync()
        syncScope.cancel()
    }

    companion object {
        private const val TAG = "QuickShareSync"
        private const val SERVICE_ID = "com.example.multicamapp.quickshare"
        private const val TCP_PORT = 8988
        private const val UDP_PORT = 8989
        private const val SYNC_PHOTO_LEAD_TIME_MS = 250L
        private const val SYNC_RECORD_LEAD_TIME_MS = 300L
    }
}
