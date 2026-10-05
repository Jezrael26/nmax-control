package com.nmaxcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var actionView: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: Button

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BikeState.learnedKey = getSharedPreferences("nmax", MODE_PRIVATE).getString("learned", null)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "NMAX Control"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })

        statusView = TextView(this).apply { textSize = 16f; setPadding(0, dp(8), 0, 0) }
        root.addView(statusView)

        actionView = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(actionView)

        startBtn = Button(this).apply {
            setOnClickListener { onStartStop() }
        }
        root.addView(startBtn)

        root.addView(Button(this).apply {
            text = "Scan & choose bike"
            setOnClickListener { onScan() }
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(Button(this).apply {
            text = "Learn Play/Pause"
            setOnClickListener {
                BikeState.learnUntil = System.currentTimeMillis() + 15000
                BikeState.lastAction = "Press Play/Pause on the bike now (15 seconds)"
            }
        }, half)
        row.addView(Button(this).apply {
            text = "Share log"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, BikeState.text(300, false))
                }
                startActivity(Intent.createChooser(send, "Share log"))
            }
        }, half)
        root.addView(row)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        val scroll = ScrollView(this)
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun refresh() {
        statusView.text = "Status: " + BikeState.status
        val learning = if (BikeState.isLearning()) "\nLEARNING: press Play/Pause on the bike now" else ""
        actionView.text = "Last: " + BikeState.lastAction +
            "\nPlay/Pause code: " + (BikeState.learnedKey ?: "not learned yet") + learning
        startBtn.text = if (BikeState.running) "Stop" else "Start"
        logView.text = BikeState.text(120, true)
    }

    private fun onStartStop() {
        if (BikeState.running) {
            stopService(Intent(this, BikeService::class.java))
            return
        }
        val missing = neededPermissions()
        if (missing.isEmpty()) startBike() else requestPermissions(missing, 1)
    }

    @SuppressLint("MissingPermission")
    private fun onScan() {
        val missing = neededPermissions()
        if (missing.isNotEmpty()) {
            requestPermissions(missing, 2)
            Toast.makeText(this, "Allow the permissions, then tap Scan again", Toast.LENGTH_LONG).show()
            return
        }
        val scanner = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "Bluetooth is off", Toast.LENGTH_LONG).show()
            return
        }

        val labels = ArrayList<String>()
        val addresses = ArrayList<String>()
        val seen = HashSet<String>()
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val listView = ListView(this)
        listView.adapter = listAdapter

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == BikeService.SERVICE_UUID } == true
                if (name?.startsWith(BikeService.NAME_PREFIX) != true && !hasService) return
                val key = "${result.device.address}|${result.isConnectable}|${result.isLegacy}"
                if (!seen.add(key)) return
                labels.add(
                    "${name ?: "?"}\n${result.device.address}  connectable=${result.isConnectable}" +
                        "  legacy=${result.isLegacy}  rssi=${result.rssi}"
                )
                addresses.add(result.device.address)
                listAdapter.notifyDataSetChanged()
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .build()
        scanner.startScan(null, settings, callback)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Scanning for speedometers...")
            .setView(listView)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                // ignore
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val addr = addresses[position]
            dialog.dismiss()
            startForegroundService(Intent(this, BikeService::class.java).putExtra("address", addr))
        }
        dialog.show()
    }

    private fun startBike() {
        startForegroundService(Intent(this, BikeService::class.java))
    }

    private fun neededPermissions(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        val btMissing = neededPermissions().any { it != Manifest.permission.POST_NOTIFICATIONS }
        if (btMissing) {
            Toast.makeText(this, "Bluetooth permission is needed", Toast.LENGTH_LONG).show()
        } else {
            startBike()
        }
    }
}
