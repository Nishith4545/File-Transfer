package com.transfersync.app
import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException


class MainActivity : AppCompatActivity() {

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: WiFiDirectBroadcastReceiver
    private val intentFilter = IntentFilter()

    private lateinit var btnHost: Button
    private lateinit var btnClient: Button
    private lateinit var btnDiscover: Button
    private lateinit var btnSendFile: Button
    private lateinit var txtRole: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private lateinit var listPeers: ListView
    private lateinit var progressBar: ProgressBar

    private var isHost = false
    private var peers: MutableList<WifiP2pDevice> = mutableListOf()
    private var hostAddress: InetAddress? = null
    private var connectedDeviceAddress: InetAddress? = null
    private var isConnected = false
    private var isServerReady = false

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    sendFileViaSocket(uri)
                }
            }
        }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initWifiDirect()
        setupClickListeners()
        checkAndRequestPermissions()
    }

    private fun initViews() {
        btnHost = findViewById(R.id.btnHost)
        btnClient = findViewById(R.id.btnClient)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnSendFile = findViewById(R.id.btnSendFile)
        txtRole = findViewById(R.id.txtRole)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        listPeers = findViewById(R.id.listPeers)
        progressBar = findViewById(R.id.progressBar)

        btnSendFile.isEnabled = false
        progressBar.visibility = View.GONE
    }

    private fun initWifiDirect() {
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        receiver = WiFiDirectBroadcastReceiver()
        receiver.initialize(manager, channel, this)

        intentFilter.apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }

    private fun setupClickListeners() {
        btnHost.setOnClickListener {
            if (!checkWifiPermissions()) {
                appendLog("✗ Missing WiFi permissions")
                return@setOnClickListener
            }

            resetConnection()
            isHost = true
            txtRole.text = "Role: Host"
            appendLog("=== HOST MODE ===")

            // Force disconnect from router
            scope.launch {
                if (disconnectFromRouter()) {
                    delay(2000) // Wait for WiFi to stabilize
                    withContext(Dispatchers.Main) {
                        createGroupAsHost()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        createGroupAsHost()
                    }
                }
            }
        }

        btnClient.setOnClickListener {
            if (!checkWifiPermissions()) {
                appendLog("✗ Missing WiFi permissions")
                return@setOnClickListener
            }

            resetConnection()
            isHost = false
            txtRole.text = "Role: Client"
            appendLog("=== CLIENT MODE ===")

            // Force disconnect from router
            scope.launch {
                if (disconnectFromRouter()) {
                    delay(2000) // Wait for WiFi to stabilize
                    withContext(Dispatchers.Main) {
                        startDiscovery()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        startDiscovery()
                    }
                }
            }
        }

        btnDiscover.setOnClickListener {
            if (!checkWifiPermissions()) {
                appendLog("✗ Missing WiFi permissions")
                return@setOnClickListener
            }

            if (!isHost) {
                startDiscovery()
            } else {
                appendLog("Host doesn't need discovery")
            }
        }

        listPeers.setOnItemClickListener { _, _, position, _ ->
            if (!checkWifiPermissions()) {
                appendLog("✗ Missing WiFi permissions")
                return@setOnItemClickListener
            }

            if (!isHost && !isConnected) {
                val device = peers[position]
                connectToDevice(device)
            }
        }

        btnSendFile.setOnClickListener {
            if (isConnected) {
                pickFile()
            } else {
                appendLog("✗ Not connected! Cannot send file.")
            }
        }
    }

    private suspend fun disconnectFromRouter(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

                if (!wifiManager.isWifiEnabled) {
                    appendLog("⚡ Enabling WiFi...")
                    wifiManager.isWifiEnabled = true
                    delay(3000)
                    return@withContext true
                }

                val networkId = wifiManager.connectionInfo.networkId
                if (networkId != -1) {
                    appendLog("🔌 Disconnecting from router...")
                    wifiManager.disconnect()
                    delay(500)

                    // Remove network to prevent auto-reconnect
                    wifiManager.disableNetwork(networkId)
                    wifiManager.removeNetwork(networkId)
                    wifiManager.saveConfiguration()

                    appendLog("✓ Disconnected from router")
                    return@withContext true
                }

                false
            } catch (e: Exception) {
                appendLog("⚠️ Error disconnecting: ${e.message}")
                false
            }
        }
    }

    private fun resetConnection() {
        isConnected = false
        isServerReady = false
        hostAddress = null
        connectedDeviceAddress = null

        // Cancel server job first
        serverJob?.cancel()
        serverJob = null

        // Close socket with retry
        scope.launch {
            try {
                serverSocket?.close()
                delay(500) // Wait for port to be released
            } catch (e: Exception) {
                Log.w("WFD", "Error closing socket: ${e.message}")
            }
            serverSocket = null
        }

        try {
            if (checkWifiPermissions()) {
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        appendLog("✓ Group removed")
                    }
                    override fun onFailure(reason: Int) {}
                })
                manager.cancelConnect(channel, null)
                manager.stopPeerDiscovery(channel, null)
            }
        } catch (e: SecurityException) {
            Log.w("WFD", "Permission issue during reset: ${e.message}")
        }

        runOnUiThread {
            txtStatus.text = "Status: Disconnected"
            btnSendFile.text = "Send File"
            btnSendFile.isEnabled = false
            progressBar.visibility = View.GONE
            progressBar.progress = 0
            peers.clear()
            listPeers.adapter = null
        }
    }

    private fun checkWifiPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val nearbyDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!fineLocation || !nearbyDevices) {
            checkAndRequestPermissions()
            return false
        }
        return true
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        // Storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - Use READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, etc.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12 - Request MANAGE_EXTERNAL_STORAGE or regular storage
            if (!Environment.isExternalStorageManager()) {
                appendLog("⚠️ Need storage permission - please grant in settings")
                Toast.makeText(this, "Please grant 'All files access' permission", Toast.LENGTH_LONG).show()

                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            // Android 10 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun createGroupAsHost() {
        try {
            if (!checkWifiPermissions()) return

            // Remove any existing group first
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // Now create new group
                    manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            appendLog("✓ Group created successfully")
                            appendLog("⏳ Waiting for client to connect...")
                        }
                        override fun onFailure(reason: Int) {
                            appendLog("✗ Failed to create group: ${getErrorMessage(reason)}")
                            if (reason == WifiP2pManager.BUSY) {
                                appendLog("🔄 Retrying in 2 seconds...")
                                Handler(Looper.getMainLooper()).postDelayed({
                                    createGroupAsHost()
                                }, 2000)
                            }
                        }
                    })
                }
                override fun onFailure(reason: Int) {
                    // Group didn't exist, create new one
                    manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            appendLog("✓ Group created successfully")
                            appendLog("⏳ Waiting for client to connect...")
                        }
                        override fun onFailure(reason: Int) {
                            appendLog("✗ Failed to create group: ${getErrorMessage(reason)}")
                        }
                    })
                }
            })
        } catch (e: SecurityException) {
            appendLog("✗ Permission denied: ${e.message}")
            checkAndRequestPermissions()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun startDiscovery() {
        try {
            if (!checkWifiPermissions()) return

            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    appendLog("✓ Discovery started...")
                }
                override fun onFailure(reason: Int) {
                    appendLog("✗ Discovery failed: ${getErrorMessage(reason)}")
                    if (reason == WifiP2pManager.BUSY) {
                        appendLog("🔄 Retrying in 2 seconds...")
                        Handler(Looper.getMainLooper()).postDelayed({
                            startDiscovery()
                        }, 2000)
                    }
                }
            })
        } catch (e: SecurityException) {
            appendLog("✗ Permission denied: ${e.message}")
            checkAndRequestPermissions()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun connectToDevice(device: WifiP2pDevice) {
        try {
            if (!checkWifiPermissions()) return

            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }

            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    appendLog("✓ Connection initiated to ${device.deviceName}")
                }
                override fun onFailure(reason: Int) {
                    appendLog("✗ Connection failed: ${getErrorMessage(reason)}")
                }
            })
        } catch (e: SecurityException) {
            appendLog("✗ Permission denied: ${e.message}")
            checkAndRequestPermissions()
        }
    }

    fun updatePeerList(list: List<WifiP2pDevice>) {
        peers.clear()
        peers.addAll(list)
        val names = list.map { "${it.deviceName} [${it.status.toStatusString()}]" }

        runOnUiThread {
            listPeers.adapter = ArrayAdapter(this, androidx.appcompat.R.layout.support_simple_spinner_dropdown_item, names)
            appendLog("📱 Found ${list.size} peer(s)")
        }
    }

    private fun Int.toStatusString(): String = when(this) {
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }

    fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        hostAddress = info.groupOwnerAddress
        isConnected = info.groupFormed

        appendLog("━━━━━━━━━━━━━━━━━━━━")
        appendLog("✓ CONNECTION ESTABLISHED")
        appendLog("🌐 Host IP: ${hostAddress?.hostAddress}")
        appendLog("👤 I am: ${if (info.isGroupOwner) "GROUP OWNER (Host)" else "CLIENT"}")
        appendLog("━━━━━━━━━━━━━━━━━━━━")

        runOnUiThread {
            txtStatus.text = "Status: Connected ✓"
            btnSendFile.text = "📤 Send File to ${if (isHost) "Client" else "Host"}"
            btnSendFile.isEnabled = true
        }

        // Start server FIRST (both devices)
        startBidirectionalServer()

        // Then send IP handshake (client only, with delay)
        if (!info.isGroupOwner) {
            scope.launch {
                // Wait for server to be fully ready
                var retries = 0
                while (!isServerReady && retries < 50) {
                    delay(100)
                    retries++
                }

                if (!isServerReady) {
                    appendLog("⚠️ Server not ready, waiting 2 more seconds...")
                    delay(2000)
                }

                sendIpToHost(info.groupOwnerAddress)
            }
        }
    }

    private suspend fun sendIpToHost(hostAddr: InetAddress) {
        withContext(Dispatchers.IO) {
            var retries = 0
            val maxRetries = 5

            while (retries < maxRetries) {
                try {
                    appendLog("📡 Sending my IP to host (attempt ${retries + 1}/$maxRetries)...")

                    val socket = Socket()
                    socket.soTimeout = 5000
                    socket.connect(InetSocketAddress(hostAddr.hostAddress, 8988), 5000)

                    val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    val myIp = getLocalIpAddress()

                    out.writeUTF("CLIENT_IP::$myIp")
                    out.flush()

                    socket.close()

                    appendLog("✓ IP handshake successful ($myIp)")
                    return@withContext

                } catch (e: Exception) {
                    retries++
                    if (retries < maxRetries) {
                        appendLog("⚠️ Retry in 1s... (${e.message})")
                        delay(1000)
                    } else {
                        appendLog("✗ Failed to send IP: ${e.message}")
                        appendLog("ℹ️ You can still receive files. Send a file first to enable bidirectional transfer.")
                    }
                }
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.startsWith("192.168.49.")) { // WiFi Direct subnet
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WFD", "Error getting IP: ${e.message}")
        }
        return ""
    }

    private fun startBidirectionalServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            var bindRetries = 0
            val maxRetries = 3

            while (bindRetries < maxRetries) {
                try {
                    // Close existing socket completely
                    serverSocket?.close()
                    delay(500) // Wait for port release

                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        soTimeout = 0
                        bind(InetSocketAddress(8988))
                    }

                    isServerReady = true
                    appendLog("✓ Server ready on port 8988")
                    break

                } catch (e: BindException) {
                    bindRetries++
                    if (bindRetries < maxRetries) {
                        appendLog("⚠️ Port busy, retrying in 1s...")
                        delay(1000)
                    } else {
                        appendLog("✗ Failed to bind port: ${e.message}")
                        return@launch
                    }
                }
            }

            // Accept connections loop
            while (isActive && isConnected) {
                try {
                    val client = withContext(Dispatchers.IO) {
                        serverSocket?.accept()
                    } ?: break

                    appendLog("📥 Incoming connection from ${client.inetAddress.hostAddress}")

                    // Handle each connection in separate coroutine
                    launch { handleIncomingConnection(client) }

                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (isActive && isConnected) {
                        appendLog("⚠️ Connection error: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    private suspend fun handleIncomingConnection(client: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val input = DataInputStream(BufferedInputStream(client.getInputStream(), 8192))
                val firstData = input.readUTF()

                // Check if it's IP handshake
                if (firstData.startsWith("CLIENT_IP::")) {
                    val ip = firstData.substringAfter("CLIENT_IP::")
                    connectedDeviceAddress = InetAddress.getByName(ip)
                    appendLog("✓ Received client IP: $ip")
                    client.close()
                    return@withContext
                }

                // Otherwise it's a file transfer
                val fileName = firstData
                val fileSize = input.readLong()

                // Store sender's IP
                connectedDeviceAddress = client.inetAddress
                appendLog("📥 Receiving: $fileName (${formatFileSize(fileSize)})")

                // Always try MediaStore first for Android 10+, fallback to direct file write
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveFileToDownloads(fileName, fileSize, input)
                } else {
                    // Android 9 and below - direct file write to Downloads
                    saveToDownloadsLegacy(fileName, fileSize, input)
                }

                return@withContext

            } catch (e: Exception) {
                appendLog("✗ Receive error: ${e.message}")
                e.printStackTrace()
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveFileToDownloads(fileName: String, fileSize: Long, input: DataInputStream) {
        withContext(Dispatchers.IO) {
            var uri: Uri? = null
            val resolver = contentResolver
            try {
                val mimeType = getMimeType(fileName)

                appendLog("📝 MIME Type: $mimeType")

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.SIZE, fileSize)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1) // Mark as pending during write
                }

                // Always use Downloads collection regardless of file type
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                if (uri == null) {
                    appendLog("✗ Failed to create file in Downloads")
                    // Try alternative method
                    saveToDownloadsAlternative(fileName, fileSize, input)
                    return@withContext
                }

                appendLog("✓ Created file entry in Downloads")

                resolver.openOutputStream(uri, "w")?.use { output ->
                    val buffer = ByteArray(8 * 1024 * 1024)
                    var bytesRead: Int
                    var totalBytes = 0L
                    var lastUpdate = System.currentTimeMillis()

                    runOnUiThread {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = 0
                    }

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 100) {
                            showReceiveProgress(totalBytes, fileSize, fileName)
                            lastUpdate = now
                        }

                        if (totalBytes >= fileSize) break
                    }

                    output.flush()
                }

                // Mark file as complete
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                appendLog("✓ File saved to Downloads: $fileName")
                showReceiveProgress(fileSize, fileSize, fileName)

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "✓ Saved to Downloads: $fileName", Toast.LENGTH_SHORT).show()
                    Handler(Looper.getMainLooper()).postDelayed({
                        progressBar.visibility = View.GONE
                        txtStatus.text = "Status: Connected ✓"
                    }, 2000)
                }

            } catch (e: Exception) {
                appendLog("✗ MediaStore failed: ${e.message}")
                e.printStackTrace()

                // Clean up failed entry
                uri?.let {
                    try {
                        resolver.delete(it, null, null)
                    } catch (_: Exception) {}
                }

                // Try alternative method
                try {
                    saveToDownloadsAlternative(fileName, fileSize, input)
                } catch (fallbackError: Exception) {
                    appendLog("✗ All methods failed: ${fallbackError.message}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to save file", Toast.LENGTH_LONG).show()
                        progressBar.visibility = View.GONE
                    }
                }
            }
        }
    }

    private suspend fun saveToDownloadsAlternative(fileName: String, fileSize: Long, input: DataInputStream) {
        withContext(Dispatchers.IO) {
            try {
                appendLog("🔄 Trying alternative download method...")

                // Get the public Downloads directory using Environment
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }

                val outputFile = File(downloadsDir, fileName)
                appendLog("📁 Saving to: ${outputFile.absolutePath}")

                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8 * 1024 * 1024)
                    var bytesRead: Int
                    var totalBytes = 0L
                    var lastUpdate = System.currentTimeMillis()

                    runOnUiThread {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = 0
                    }

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 100) {
                            showReceiveProgress(totalBytes, fileSize, fileName)
                            lastUpdate = now
                        }

                        if (totalBytes >= fileSize) break
                    }

                    output.flush()
                }

                // Notify media scanner so file appears in file managers
                MediaScannerConnection.scanFile(
                    this@MainActivity,
                    arrayOf(outputFile.absolutePath),
                    null
                ) { path, uri ->
                    appendLog("✓ File indexed: $path")
                }

                appendLog("✓ File saved to Downloads: ${outputFile.name}")
                showReceiveProgress(fileSize, fileSize, fileName)

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "✓ Saved to Downloads: ${outputFile.name}", Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({
                        progressBar.visibility = View.GONE
                        txtStatus.text = "Status: Connected ✓"
                    }, 2000)
                }

            } catch (e: Exception) {
                appendLog("✗ Alternative method failed: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }

    private suspend fun saveToDownloadsLegacy(fileName: String, fileSize: Long, input: DataInputStream) {
        withContext(Dispatchers.IO) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }

                val outputFile = File(downloadsDir, fileName)
                appendLog("📁 Saving to: ${outputFile.absolutePath}")

                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8 * 1024 * 1024)
                    var bytesRead: Int
                    var totalBytes = 0L
                    var lastUpdate = System.currentTimeMillis()

                    runOnUiThread {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = 0
                    }

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 100) {
                            showReceiveProgress(totalBytes, fileSize, fileName)
                            lastUpdate = now
                        }

                        if (totalBytes >= fileSize) break
                    }

                    output.flush()
                }

                // Notify media scanner
                MediaScannerConnection.scanFile(
                    this@MainActivity,
                    arrayOf(outputFile.absolutePath),
                    null,
                    null
                )

                appendLog("✓ File saved to Downloads: ${outputFile.name}")
                showReceiveProgress(fileSize, fileSize, fileName)

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "✓ Saved to Downloads: ${outputFile.name}", Toast.LENGTH_SHORT).show()
                    Handler(Looper.getMainLooper()).postDelayed({
                        progressBar.visibility = View.GONE
                        txtStatus.text = "Status: Connected ✓"
                    }, 2000)
                }

            } catch (e: Exception) {
                appendLog("✗ Legacy save failed: ${e.message}")
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "apk" -> "application/vnd.android.package-archive"
            else -> {
                // Try to get MIME type from system
                val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                type ?: "application/octet-stream"
            }
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        pickFileLauncher.launch(intent)
    }

    private fun sendFileViaSocket(uri: Uri) {
        val targetAddress = if (isHost) {
            connectedDeviceAddress ?: run {
                appendLog("✗ Client IP unknown. Client needs to send a file first.")
                return
            }
        } else {
            hostAddress ?: run {
                appendLog("✗ No host address available")
                return
            }
        }

        scope.launch {
            var socket: Socket? = null
            try {
                val fileName = getFileName(uri)
                val fileSize = getFileSize(uri)

                appendLog("📤 Sending: $fileName (${formatFileSize(fileSize)})")
                appendLog("📤 Target: ${targetAddress.hostAddress}")

                socket = Socket()
                socket.soTimeout = 10000
                socket.connect(InetSocketAddress(targetAddress.hostAddress, 8988), 10000)

                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 8192))
                val inputStream = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")

                output.writeUTF(fileName)
                output.writeLong(fileSize)
                output.flush()

                val buffer = ByteArray(8 * 1024 * 1024)
                var bytesRead: Int
                var totalBytes = 0L
                var lastUpdate = System.currentTimeMillis()

                runOnUiThread {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 0
                }

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 100) {
                        showSendProgress(totalBytes, fileSize, fileName)
                        lastUpdate = now
                    }
                }

                output.flush()
                inputStream.close()
                socket.close()

                appendLog("✓ File sent successfully!")
                showSendProgress(fileSize, fileSize, fileName)

                runOnUiThread {
                    Handler(Looper.getMainLooper()).postDelayed({
                        progressBar.visibility = View.GONE
                        txtStatus.text = "Status: Connected ✓"
                    }, 2000)
                }

            } catch (e: Exception) {
                appendLog("✗ Send error: ${e.message}")
                runOnUiThread {
                    progressBar.visibility = View.GONE
                }
            } finally {
                socket?.close()
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null

        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }

        if (result == null) {
            result = uri.path
            val cut = result!!.lastIndexOf('/')
            if (cut != -1) {
                result = result!!.substring(cut + 1)
            }
        }
        return result ?: "unknown_file"
    }

    private fun getFileSize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst()) {
                return cursor.getLong(sizeIndex)
            }
        }
        return 0
    }

    private fun formatFileSize(size: Long): String {
        val kb = size / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> "%.2f GB".format(gb)
            mb >= 1 -> "%.2f MB".format(mb)
            kb >= 1 -> "%.2f KB".format(kb)
            else -> "$size B"
        }
    }

    private fun showSendProgress(sent: Long, total: Long, fileName: String) {
        runOnUiThread {
            val percent = if (total > 0) (sent * 100 / total).toInt() else 0
            val speed = calculateSpeed(sent)
            progressBar.progress = percent
            txtStatus.text = "📤 Sending: $percent% | $speed MB/s\n$fileName"
        }
    }

    private fun showReceiveProgress(received: Long, total: Long, fileName: String) {
        runOnUiThread {
            val percent = if (total > 0) (received * 100 / total).toInt() else 0
            val speed = calculateSpeed(received)
            progressBar.progress = percent
            txtStatus.text = "📥 Receiving: $percent% | $speed MB/s\n$fileName"
        }
    }

    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()

    private fun calculateSpeed(currentBytes: Long): String {
        val now = System.currentTimeMillis()
        val timeDiff = now - lastTime

        if (timeDiff > 0) {
            val bytesDiff = currentBytes - lastBytes
            val speedMBps = (bytesDiff / timeDiff) / 1024.0
            lastBytes = currentBytes
            lastTime = now
            return "%.2f".format(speedMBps)
        }
        return "0.00"
    }

    private fun getErrorMessage(reason: Int): String = when(reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.ERROR -> "Internal error"
        WifiP2pManager.BUSY -> "Busy"
        else -> "Error ($reason)"
    }

    fun appendLog(msg: String) {
        Log.d("WFD", msg)
        runOnUiThread {
            txtLog.append("$msg\n")
            val scrollAmount = txtLog.layout?.getLineTop(txtLog.lineCount) ?: 0
            if (scrollAmount > txtLog.height) {
                txtLog.scrollTo(0, scrollAmount - txtLog.height)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        resetConnection()
        scope.cancel()
    }
}
