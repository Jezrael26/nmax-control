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
        val SERVICE_UUID: UUID = UUID.fromString(
            "afa2cdf4-eccf-46a7-a5ea-9da428c0157a"
        )

        val CCCD: UUID = UUID.fromString(
            "00002902-0000-1000-8000-00805f9b34fb"
        )

        val NOTIFY_UUIDS: List<UUID> = listOf(
            "9c810d26-b605-4306-8c1c-755a1ba3066c",
            "75ab2788-87f6-4f61-ba9c-6f85a12600d1",
            "77808451-e42f-4f6b-83ed-3ab44aa3d16a",
            "347456ee-a588-4dd5-bd98-62be3672e27a",
            "e974b9a1-fa31-43c6-91ae-09931f3f6ef1"
        ).map { UUID.fromString(it) }

        const val BUTTONS = "9c810d26"
        const val STREAM = "e974b9a1"

        const val VOL_UP = "01-1D-05"
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

    private val seen = HashSet<String>()

    private var bestNonConnectable: ScanResult? = null

    private val pending = ArrayDeque<BluetoothGattCharacteristic>()

    private lateinit var audio: AudioManager

    private val retryRunnable = Runnable {
        findBike()
    }

    private val scanTimeout = Runnable {
    if (scanning) {
        stopScan()

        val fb = bestNonConnectable
        if (fb != null) {
            BikeState.add(
                "Found only non-connectable advertisement; " +
                "NOT attempting GATT connection: ${fb.device.address}"
            )
        }

        BikeState.add(
            "No connectable BLE advertisement found"
        )

        retry(1000)
    }
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

        adapter =
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        BikeState.learnedKey =
            getSharedPreferences("nmax", MODE_PRIVATE)
                .getString("learned", null)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startAsForeground()

        val chosen = intent?.getStringExtra("address")

        if (!BikeState.running) {
            BikeState.running = true
            BikeState.status = "Starting..."

            BikeState.add("Service started")

            if (chosen == null) {
                findBike()
            }
        }

        if (chosen != null) {

            handler.removeCallbacks(retryRunnable)

            stopScan()

            val dev = adapter?.getRemoteDevice(chosen)

            if (dev != null) {
                BikeState.add(
                    "Connecting to the chosen device $chosen"
                )

                connect(dev)
            }
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

    // ---------------------------------------------------------
    // FOREGROUND SERVICE
    // ---------------------------------------------------------

    private fun startAsForeground() {

        val nm =
            getSystemService(NOTIFICATION_SERVICE)
                    as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bike connection",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val n =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("NMAX Control")
                .setContentText("Listening to the bike buttons")
                .setSmallIcon(
                    android.R.drawable.stat_sys_data_bluetooth
                )
                .setContentIntent(pi)
                .setOngoing(true)
                .build()

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                1,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )

        } else {

            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------
    // RETRY
    // ---------------------------------------------------------

    private fun retry(ms: Long) {

        handler.removeCallbacks(retryRunnable)

        handler.postDelayed(
            retryRunnable,
            ms
        )
    }

    // ---------------------------------------------------------
    // FIND BIKE
    // ---------------------------------------------------------

    private fun findBike() {

        if (!BikeState.running) return

        val a = adapter

        if (a == null || !a.isEnabled) {

            BikeState.status =
                "Bluetooth is off - please turn it on"

            retry(3000)

            return
        }

        val mgr =
            getSystemService(BLUETOOTH_SERVICE)
                    as BluetoothManager

        val live =
            mgr.getConnectedDevices(
                BluetoothProfile.GATT
            )

        BikeState.add(
            "Phone-connected devices: " +
                live.joinToString {
                    "${it.name}/${it.address}"
                }
        )

        val joined =
            live.firstOrNull {
                it.name?.startsWith(NAME_PREFIX) == true
            }

        if (joined != null) {

            BikeState.add(
                "Bike is already connected to this phone, joining"
            )

            connect(joined, true)

            return
        }

        val bonded =
            a.bondedDevices?.firstOrNull {
                it.name?.startsWith(NAME_PREFIX) == true
            }

        val useBonded =
            bonded != null && attempt % 2 == 0

        attempt++

        if (useBonded && bonded != null) {

            BikeState.add(
                "Trying paired device ${bonded.address}"
            )

            connect(bonded, true)

        } else {

            startScan()
        }
    }

    // ---------------------------------------------------------
    // BLE SCAN
    // ---------------------------------------------------------

    private fun startScan() {

        val scanner =
            adapter?.bluetoothLeScanner

        if (scanner == null) {

            retry(3000)

            return
        }

        BikeState.status =
            "Searching for the bike..."

        seen.clear()

        bestNonConnectable = null

        scanning = true

        BikeState.add("BLE scan started")

        /*
         * IMPORTANT:
         * Do not force legacy=false.
         * The NMAX advertisement we observed can be legacy.
         */

        scanner.startScan(
            null,
            ScanSettings.Builder()
                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                )
                .build(),
            scanCallback
        )

        handler.postDelayed(
            scanTimeout,
            15000
        )
    }

    private fun stopScan() {

        if (!scanning) return

        scanning = false

        handler.removeCallbacks(scanTimeout)

        try {

            adapter
                ?.bluetoothLeScanner
                ?.stopScan(scanCallback)

        } catch (e: Exception) {

            // ignore
        }
    }

    private val scanCallback =
        object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                if (!scanning) return

                val name =
                    result.scanRecord?.deviceName
                        ?: result.device.name

                val hasService =
                    result.scanRecord
                        ?.serviceUuids
                        ?.any {
                            it.uuid == SERVICE_UUID
                        } == true

                if (
                    name?.startsWith(NAME_PREFIX) != true &&
                    !hasService
                ) {
                    return
                }

                val key =
                    "${result.device.address}|${result.isConnectable}"

                if (seen.add(key)) {

                    val raw =
                        result.scanRecord
                            ?.bytes
                            ?.take(32)
                            ?.joinToString("") {
                                "%02X".format(it)
                            }

                    BikeState.add(
                        "Seen $name " +
                            "${result.device.address} " +
                            "connectable=${result.isConnectable} " +
                            "legacy=${result.isLegacy} " +
                            "rssi=${result.rssi} " +
                            "raw=$raw"
                    )
                }

               if (result.isConnectable) {
    stopScan()
    connect(result.device)
} else {
    val b = bestNonConnectable

    if (b == null || result.rssi > b.rssi) {
        bestNonConnectable = result
    }
}
            }

            override fun onScanFailed(
                errorCode: Int
            ) {

                scanning = false

                BikeState.add(
                    "Scan failed: $errorCode"
                )

                retry(3000)
            }
        }

    // ---------------------------------------------------------
    // GATT CONNECT
    // ---------------------------------------------------------

    private fun connect(
        device: BluetoothDevice,
        auto: Boolean = false
    ) {

        BikeState.status =
            "Connecting..."

        BikeState.add(
            "Opening GATT connection to " +
                "${device.name}/${device.address}"
        )

        closeGatt()

        gatt =
            device.connectGatt(
                this,
                auto,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

        handler.postDelayed(
            connectTimeout,
            if (auto) 90000L else 20000L
        )
    }

    private fun closeGatt() {

        handler.removeCallbacks(
            connectTimeout
        )

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

            try {
                g.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // ---------------------------------------------------------
    // GATT CALLBACK
    // ---------------------------------------------------------

    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                if (
                    newState ==
                    BluetoothProfile.STATE_CONNECTED
                ) {

                    if (gatt !== g) return

                    connected = true

                    handler.removeCallbacks(
                        connectTimeout
                    )

                    BikeState.status =
                        "Connected, setting up..."

                    BikeState.add(
                        "Connected (status $status)"
                    )

                    /*
                     * Give Android/BLE stack a short moment
                     * before service discovery.
                     */

                    handler.postDelayed({

                        if (gatt === g) {

                            BikeState.add(
                                "Starting service discovery..."
                            )

                            g.discoverServices()
                        }

                    }, 600)

                } else {

                    BikeState.add(
                        "Disconnected (status $status)"
                    )

                    if (gatt === g) {

                        closeGatt()

                        BikeState.status =
                            "Disconnected - reconnecting..."

                        retry(3000)

                    } else {

                        try {
                            g.close()
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }

            // -------------------------------------------------
            // SERVICES DISCOVERED
            // -------------------------------------------------

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int
            ) {

                if (gatt !== g) return

                BikeState.add(
                    "Services discovered: status=$status"
                )

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    BikeState.add(
                        "Service discovery failed: $status"
                    )

                    g.disconnect()

                    return
                }

                val svc =
                    g.getService(SERVICE_UUID)

                if (svc == null) {

                    BikeState.add(
                        "Bike service NOT found: " +
                            SERVICE_UUID
                    )

                    BikeState.add(
                        "Available services: " +
                            g.services.joinToString {
                                it.uuid.toString()
                            }
                    )

                    g.disconnect()

                    return
                }

                BikeState.add(
                    "NMAX service FOUND: ${svc.uuid}"
                )

                /*
                 * Diagnostic:
                 * show every characteristic and its
                 * Android GATT properties.
                 */

                for (ch in svc.characteristics) {

                    BikeState.add(
                        "CHAR ${ch.uuid} " +
                            "properties=${ch.properties}"
                    )
                }

                pending.clear()

                for (u in NOTIFY_UUIDS) {

                    val ch =
                        svc.getCharacteristic(u)

                    if (ch != null) {

                        BikeState.add(
                            "Characteristic FOUND: " +
                                ch.uuid
                        )

                        pending.addLast(ch)

                    } else {

                        BikeState.add(
                            "Characteristic missing: $u"
                        )
                    }
                }

                /*
                 * SDMV analysis showed MTU 512 being requested.
                 * We reproduce the standard BLE MTU negotiation,
                 * without reproducing proprietary authentication.
                 */

                BikeState.add(
                    "Requesting MTU 512..."
                )

                val mtuStarted =
                    try {

                        g.requestMtu(512)

                    } catch (e: Exception) {

                        BikeState.add(
                            "MTU request exception: " +
                                e.message
                        )

                        false
                    }

                if (!mtuStarted) {

                    BikeState.add(
                        "MTU request could not start; " +
                            "continuing"
                    )

                    subscribeNext(g)
                }
            }

            // -------------------------------------------------
            // MTU RESULT
            // -------------------------------------------------

            override fun onMtuChanged(
                g: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {

                if (gatt !== g) return

                BikeState.add(
                    "MTU result: mtu=$mtu status=$status"
                )

                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    BikeState.add(
                        "MTU negotiation successful"
                    )

                } else {

                    BikeState.add(
                        "MTU negotiation failed; " +
                            "using default MTU"
                    )
                }

                /*
                 * Continue to notification setup after MTU
                 * callback completes.
                 */

                subscribeNext(g)
            }

            // -------------------------------------------------
            // DESCRIPTOR WRITE
            // -------------------------------------------------

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                d: BluetoothGattDescriptor,
                status: Int
            ) {

                if (gatt !== g) return

                BikeState.add(
                    "Descriptor write: " +
                        "${d.characteristic.uuid} " +
                        "status=$status"
                )

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    BikeState.add(
                        "Notification enable failed for " +
                            d.characteristic.uuid
                    )
                }

                subscribeNext(g)
            }

            // -------------------------------------------------
            // CHARACTERISTIC CHANGED - ANDROID 13+
            // -------------------------------------------------

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                if (gatt !== g) return

                logNotification(
                    ch.uuid,
                    value
                )

                handlePacket(
                    ch.uuid,
                    value
                )
            }

            // -------------------------------------------------
            // CHARACTERISTIC CHANGED - ANDROID 12 AND BELOW
            // -------------------------------------------------

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic
            ) {

                if (gatt !== g) return

                if (Build.VERSION.SDK_INT < 33) {

                    val v =
                        ch.value ?: return

                    logNotification(
                        ch.uuid,
                        v
                    )

                    handlePacket(
                        ch.uuid,
                        v
                    )
                }
            }
        }

    // ---------------------------------------------------------
    // NOTIFICATION SETUP
    // ---------------------------------------------------------

    private fun subscribeNext(
        g: BluetoothGatt
    ) {

        if (gatt !== g) return

        val ch =
            pending.removeFirstOrNull()

        if (ch == null) {

            BikeState.status =
                "Connected - ready"

            BikeState.add(
                "Notifications enabled"
            )

            return
        }

        BikeState.add(
            "Enabling notification: ${ch.uuid}"
        )

        val notificationSet =
            try {

                g.setCharacteristicNotification(
                    ch,
                    true
                )

            } catch (e: Exception) {

                BikeState.add(
                    "setCharacteristicNotification " +
                        "exception: ${e.message}"
                )

                false
            }

        if (!notificationSet) {

            BikeState.add(
                "setCharacteristicNotification " +
                    "returned false for ${ch.uuid}"
            )
        }

        val d =
            ch.getDescriptor(CCCD)

        if (d == null) {

            BikeState.add(
                "No CCCD for ${ch.uuid}; skipping"
            )

            subscribeNext(g)

            return
        }

        val ok: Boolean

        if (Build.VERSION.SDK_INT >= 33) {

            ok =
                g.writeDescriptor(
                    d,
                    BluetoothGattDescriptor
                        .ENABLE_NOTIFICATION_VALUE
                ) ==
                    BluetoothStatusCodes.SUCCESS

        } else {

            d.value =
                BluetoothGattDescriptor
                    .ENABLE_NOTIFICATION_VALUE

            ok =
                g.writeDescriptor(d)
        }

        if (!ok) {

            BikeState.add(
                "Could not start descriptor write " +
                    "for ${ch.uuid}"
            )

            subscribeNext(g)
        }
    }

    // ---------------------------------------------------------
    // NOTIFICATION LOG
    // ---------------------------------------------------------

    private fun logNotification(
        uuid: UUID,
        value: ByteArray
    ) {

        val hex =
            value.joinToString("-") {
                "%02X".format(it)
            }

        BikeState.add(
            "NOTIFY ${uuid}: $hex"
        )
    }

    // ---------------------------------------------------------
    // BIKE PACKETS
    // ---------------------------------------------------------

    private fun handlePacket(
        uuid: UUID,
        v: ByteArray
    ) {

        val short =
            uuid.toString()
                .substring(0, 8)

        val hex =
            v.joinToString("-") {
                "%02X".format(it)
            }

        if (short == STREAM) {

            BikeState.addStream(
                v,
                hex
            )

            return
        }

        BikeState.add(
            "$short  $hex"
        )

        val key =
            "$short|$hex"

        val isVolume =
            short == BUTTONS &&
                (
                    hex == VOL_UP ||
                    hex == VOL_DOWN
                )

        if (
            BikeState.isLearning() &&
            !isVolume
        ) {

            BikeState.learnUntil = 0L

            BikeState.learnedKey = key

            getSharedPreferences(
                "nmax",
                MODE_PRIVATE
            )
                .edit()
                .putString(
                    "learned",
                    key
                )
                .apply()

            BikeState.lastAction =
                "Learned Play/Pause = $key"

            return
        }

        when {

            short == BUTTONS &&
                hex == VOL_UP -> {

                volume(true)
            }

            short == BUTTONS &&
                hex == VOL_DOWN -> {

                volume(false)
            }

            short == BUTTONS &&
                (
                    hex == "01-17-03" ||
                    hex == "01-17-04"
                ) -> {

                playPause()
            }

            short == BUTTONS &&
                hex == "01-17-01" -> {

                mediaKey(
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    "Next track"
                )
            }

            short == BUTTONS &&
                hex == "01-17-02" -> {

                volume(false)
            }

            key == BikeState.learnedKey -> {

                playPause()
            }
        }
    }

    // ---------------------------------------------------------
    // MEDIA CONTROL
    // ---------------------------------------------------------

    private fun volume(
        up: Boolean
    ) {

        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,

            if (up)
                AudioManager.ADJUST_RAISE
            else
                AudioManager.ADJUST_LOWER,

            AudioManager.FLAG_SHOW_UI
        )

        BikeState.lastAction =
            if (up)
                "Volume up"
            else
                "Volume down"
    }

    private fun mediaKey(
        code: Int,
        label: String
    ) {

        audio.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                code
            )
        )

        audio.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                code
            )
        )

        BikeState.lastAction = label
    }

    private fun playPause() {

        mediaKey(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "Play/Pause"
        )
    }
}
