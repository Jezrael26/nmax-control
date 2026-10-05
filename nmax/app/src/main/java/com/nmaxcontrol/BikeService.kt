package com.nmaxcontrol

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import java.util.UUID

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class BikeService : Service() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("afa2cdf4-eccf-46a7-a5ea-9da428c0157a")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUIDS: List<UUID> = listOf(
            "9c810d26-b605-4306-8c1c-755a1ba3066c",
            "75ab2788-87f6-4f61-ba9c-6f85a12600d1",
            "77808451-e42f-4f6b-83ed-3ab44aa3d16a",
            "347456ee-a588-4dd5-bd98-62be3672e27a",
            "e974b9a1-fa31-43c6-91ae-09931f3f6ef1"
        ).map { UUID.fromString(it) }

        const val BUTTONS = "9c810d26"   // first 8 chars of the characteristic UUID
        const val STREAM = "e974b9a1"
        const val VOL_UP = "01-1D-05"     // guess from the log: confirm on the device
        const val VOL_DOWN = "01-1D-06"
        const val NAME_PREFIX = "YCCU_"
        const val CHANNEL_ID = "bike"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var connected = false
    private var attempt = 0
    private val pending = ArrayDeque<BluetoothGattCharacteristic>()
    private lateinit var audio: AudioManager

    private val retryRunnable = Runnable { findBike() }
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            BikeState.add("Scan timeout, trying again")
            retry(1000)
        }
    }
    private val connectTimeout = Runnable {
        if (!connected) {
            BikeState.add("Connect timeout, trying again")
            closeGatt()
            retry(500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        BikeState.learnedKey = getSharedPreferences("nmax", MODE_PRIVATE).getString("learned", null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (!BikeState.running) {
            BikeState.running = true
            BikeState.status = "Starting..."
            BikeState.add("Service started")
            findBike()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        BikeState.running = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
        BikeState.status = "Stopped"
        BikeState.add("Service stopped")
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bike connection", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NMAX Control")
            .setContentText("Listening to the bike buttons")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- finding + connecting ----------

    private fun retry(ms: Long) {
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, ms)
    }

    private fun findBike() {
        if (!BikeState.running) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            BikeState.status = "Bluetooth is off - please turn it on"
            retry(3000)
            return
        }
        val bonded = a.bondedDevices?.firstOrNull { it.name?.startsWith(NAME_PREFIX) == true }
        val useBonded = bonded != null && attempt % 2 == 0
        attempt++
        if (useBonded && bonded != null) {
            BikeState.add("Trying paired device ${bonded.address}")
            connect(bonded)
        } else {
            startScan()
        }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            retry(3000)
            return
        }
        BikeState.status = "Searching for the bike..."
        scanning = true
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )
        handler.postDelayed(scanTimeout, 15000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            // ignore
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val name = result.scanRecord?.deviceName ?: result.device.name
            val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            if (name?.startsWith(NAME_PREFIX) == true || hasService) {
                stopScan()
                BikeState.add("Found $name ${result.device.address}")
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            BikeState.add("Scan failed: $errorCode")
            retry(3000)
        }
    }

    private fun connect(device: BluetoothDevice) {
        BikeState.status = "Connecting..."
        closeGatt()
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed(connectTimeout, 20000)
    }

    private fun closeGatt() {
        handler.removeCallbacks(connectTimeout)
        pending.clear()
        connected = false
        val g = gatt
        gatt = null
        if (g != null) {
            try {
                g.disconnect()
            } catch (e: Exception) {
                // ignore
            }
            g.close()
        }
    }

    // ---------- GATT ----------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (gatt !== g) return
                connected = true
                handler.removeCallbacks(connectTimeout)
                BikeState.status = "Connected, setting up..."
                BikeState.add("Connected (status $status)")
                handler.postDelayed({ if (gatt === g) g.discoverServices() }, 600)
            } else {
                BikeState.add("Disconnected (status $status)")
                if (gatt === g) {
                    closeGatt()
                    BikeState.status = "Disconnected - reconnecting..."
                    retry(3000)
                } else {
                    g.close()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            if (svc == null) {
                BikeState.add("Bike service not found (status $status)")
                g.disconnect()
                return
            }
            pending.clear()
            for (u in NOTIFY_UUIDS) {
                svc.getCharacteristic(u)?.let { pending.addLast(it) }
            }
            subscribeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            subscribeNext(g)
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handlePacket(ch.uuid, value)
        }

        // Android 12 and below
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val v = ch.value ?: return
                handlePacket(ch.uuid, v)
            }
        }
    }

    private fun subscribeNext(g: BluetoothGatt) {
        val ch = pending.removeFirstOrNull()
        if (ch == null) {
            BikeState.status = "Connected - ready"
            BikeState.add("Notifications enabled")
            return
        }
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD)
        if (d == null) {
            subscribeNext(g)
            return
        }
        val ok: Boolean = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
        if (!ok) {
            BikeState.add("Could not enable notifications for ${ch.uuid}")
            subscribeNext(g)
        }
    }

    // ---------- reacting to the bike ----------

    private fun handlePacket(uuid: UUID, v: ByteArray) {
        val short = uuid.toString().substring(0, 8)
        val hex = v.joinToString("-") { "%02X".format(it) }

        if (short == STREAM) {
            BikeState.addStream(v, hex)
            return
        }

        BikeState.add("$short  $hex")
        val key = "$short|$hex"
        val isVolume = short == BUTTONS && (hex == VOL_UP || hex == VOL_DOWN)

        if (BikeState.isLearning() && !isVolume) {
            BikeState.learnUntil = 0L
            BikeState.learnedKey = key
            getSharedPreferences("nmax", MODE_PRIVATE).edit().putString("learned", key).apply()
            BikeState.lastAction = "Learned Play/Pause = $key"
            return
        }

        when {
            short == BUTTONS && hex == VOL_UP -> volume(true)
            short == BUTTONS && hex == VOL_DOWN -> volume(false)
            key == BikeState.learnedKey -> playPause()
        }
    }

    private fun volume(up: Boolean) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        BikeState.lastAction = if (up) "Volume up" else "Volume down"
    }

    private fun playPause() {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        BikeState.lastAction = "Play/Pause"
    }
}
