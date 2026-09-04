package com.example.multicamapp.sync

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
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
    val lastPingMs: Long = System.currentTimeMillis()
)

class QuickShareSyncManager(private val context: Context) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _role = MutableStateFlow(SyncRole.STANDALONE)
    val role: StateFlow<SyncRole> = _role.asStateFlow()

    private val _connectedNodes = MutableStateFlow<List<SyncNode>>(emptyList())
    val connectedNodes: StateFlow<List<SyncNode>> = _connectedNodes.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _statusText = MutableStateFlow("Quick Share: Standalone")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val activeNodesMap = ConcurrentHashMap<String, SyncNode>()
    private var hostEndpointId: String? = null
    private var estimatedClockOffsetMs = 0L // Worker clock offset relative to Host

    // Callbacks for local hardware execution
    var onSyncPhotoTrigger: ((sessionId: String) -> Unit)? = null
    var onSyncRecordStartTrigger: ((sessionId: String) -> Unit)? = null
    var onSyncRecordStopTrigger: (() -> Unit)? = null

    private val localDeviceName: String = run {
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
            Log.d(TAG, "Connection initiated from: ${info.endpointName} ($endpointId)")
            // Auto-accept pairing (Quick Share automatic pairing)
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
                    _statusText.value = "Quick Share: Connected to Host"
                    _isSearching.value = false
                    // Start clock synchronization
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
                _statusText.value = "Quick Share: Host disconnected"
            } else if (_role.value == SyncRole.HOST) {
                _statusText.value = "Host: ${activeNodesMap.size} node(s) connected"
            }
            updateNodesList()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Discovered endpoint: ${info.endpointName} ($endpointId)")
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
        _statusText.value = "Quick Share: Broadcasting as Host..."
        _isSearching.value = true

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        connectionsClient.startAdvertising(
            "$localDeviceName (Host)",
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Host advertising started successfully")
            _statusText.value = "Host: Waiting for worker devices..."
        }.addOnFailureListener { e ->
            Log.e(TAG, "Host advertising failed", e)
            _statusText.value = "Host error: ${e.message}"
            _isSearching.value = false
        }
    }

    fun startWorker() {
        stopSync()
        _role.value = SyncRole.WORKER
        _statusText.value = "Quick Share: Scanning for Host..."
        _isSearching.value = true

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Worker discovery started successfully")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Worker discovery failed", e)
            _statusText.value = "Discovery error: ${e.message}"
            _isSearching.value = false
        }
    }

    fun stopSync() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        activeNodesMap.clear()
        hostEndpointId = null
        estimatedClockOffsetMs = 0L
        _role.value = SyncRole.STANDALONE
        _isSearching.value = false
        _statusText.value = "Quick Share: Standalone"
        updateNodesList()
    }

    private fun updateNodesList() {
        _connectedNodes.value = activeNodesMap.values.toList()
    }

    // --- Message Handling & Time Synchronization ---

    private fun handleIncomingMessage(endpointId: String, jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            when (json.optString("action")) {
                "PING" -> {
                    // Host replies to worker with timestamp
                    val t0 = json.getLong("t0")
                    val reply = JSONObject().apply {
                        put("action", "PONG")
                        put("t0", t0)
                        put("tServer", System.currentTimeMillis())
                    }
                    sendPayload(endpointId, reply.toString())
                }
                "PONG" -> {
                    // Worker calculates clock offset
                    val t0 = json.getLong("t0")
                    val tServer = json.getLong("tServer")
                    val t1 = System.currentTimeMillis()
                    val rtt = t1 - t0
                    // Clock offset = tServer - (t0 + rtt / 2)
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming sync message", e)
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
        // Convert host target timestamp to local device time using offset
        val localTargetTime = targetHostTimeMs - estimatedClockOffsetMs
        val now = System.currentTimeMillis()
        val delayMs = (localTargetTime - now).coerceAtLeast(0)

        mainHandler.postDelayed({
            action()
        }, delayMs)
    }

    private fun sendPayload(endpointId: String, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        val payload = Payload.fromBytes(bytes)
        connectionsClient.sendPayload(endpointId, payload)
    }

    private fun broadcastToAllNodes(message: String) {
        val endpoints = activeNodesMap.keys.toList()
        if (endpoints.isEmpty()) return
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        val payload = Payload.fromBytes(bytes)
        connectionsClient.sendPayload(endpoints, payload)
    }

    // --- Public Synchronized Triggers (Called by Host UI) ---

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
            // Standalone or no workers connected: fire immediately
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

    fun onDestroy() {
        stopSync()
    }

    companion object {
        private const val TAG = "QuickShareSync"
        private const val SERVICE_ID = "com.example.multicamapp.quickshare"
        private const val SYNC_PHOTO_LEAD_TIME_MS = 250L  // 250ms lead time allows network packet arrival
        private const val SYNC_RECORD_LEAD_TIME_MS = 300L // 300ms lead time for video start
    }
}
