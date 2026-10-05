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