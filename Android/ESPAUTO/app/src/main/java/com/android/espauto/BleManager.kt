package com.android.espauto

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BleManager(private val context: Context, private val callback: BleCallback) {

    interface BleCallback {
        fun onConnectionStateChanged(isConnected: Boolean, isConnecting: Boolean, tip: String)
        fun onVideoDataReceived(data: ByteArray)
        fun onStatusDataRead(brightness: Int, temperature: Int)
        fun onRssiUpdated(rssi: Int)
        fun onDeviceFound(device: BluetoothDevice)
        fun onScanFinished(cancelled: Boolean)
    }

    private val clientCharConfigUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val bleServiceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val videoCharUuid = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val controlCharUuid = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
    private val statusCharUuid = UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb")

    private val manufacturerCompanyId = 0xE5E5

    private val cmdFrameHead = byteArrayOf(0xAA.toByte(), 0xFF.toByte())

    companion object {
        const val CMD_LED_BRIGHTNESS = 0x00
        const val CMD_BEEP = 0x12
        const val CMD_SERVO_UP = 0x13
        const val CMD_SERVO_DOWN = 0x14
        const val CMD_CAR_MOVE = 0x15
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var videoCharacteristic: BluetoothGattCharacteristic? = null
    private var deviceStatusCharacteristic: BluetoothGattCharacteristic? = null

    var isConnected = false
        private set
    var isConnecting = false
        private set
    var isScanning = false
        private set

    private val bleCmdQueue = ConcurrentLinkedQueue<ByteArray>()
    private val bleHandler = Handler(Looper.getMainLooper())
    private var isSendingCmd = false
    private val cmdLock = Any()

    private val cmdSendIntervalMs = 10L
    private val rssiReadIntervalMs = 1000L
    private val statusReadPeriodMs = 5000L

    fun hasPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    fun startScan() {
        if (!hasPermissions() || bluetoothAdapter?.isEnabled != true || isScanning) return
        isScanning = true
        scanDeviceList.clear()
        bleHandler.removeCallbacksAndMessages(null)
        bleScanner = bluetoothAdapter.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setManufacturerData(manufacturerCompanyId, byteArrayOf(), byteArrayOf())
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(listOf(filter), settings, scanCallback)
        android.util.Log.d("BleManager", "startScan called, scanner=${bleScanner != null}")
        bleHandler.postDelayed({ stopScan(cancel = false) }, 5000)
    }

    fun stopScan(cancel: Boolean = true) {
        if (!hasPermissions() || !isScanning) return
        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
        if (cancel) {
            bleHandler.removeCallbacksAndMessages(null)
            if (isConnected) {
                startReadRssiLoop()
                startReadDeviceStatusLoop()
            }
        }
        callback.onScanFinished(cancel)
    }

    private val scanDeviceList = mutableListOf<BluetoothDevice>()
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            if (!scanDeviceList.contains(dev)) {
                scanDeviceList.add(dev)
                callback.onDeviceFound(dev)
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        if (!hasPermissions() || isConnecting || isConnected) return
        isConnecting = true
        closeGatt()
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bleHandler.removeCallbacksAndMessages(null)
        bleCmdQueue.clear()
        bluetoothGatt?.disconnect()
        isConnected = false
        isConnecting = false
    }

    fun closeGatt() {
        try { bluetoothGatt?.close() } catch (_: Exception) {} finally { bluetoothGatt = null }
    }

    fun sendCarMoveCmd(speed: Int, steer: Int) {
        addCmdToQueue(byteArrayOf(cmdFrameHead[0], cmdFrameHead[1], CMD_CAR_MOVE.toByte(), speed.toByte(), steer.toByte()))
    }

    fun sendLedBrightCmd(bright: Int) {
        addCmdToQueue(byteArrayOf(cmdFrameHead[0], cmdFrameHead[1], CMD_LED_BRIGHTNESS.toByte(), bright.toByte()))
    }

    fun sendSimpleControlCmd(cmdCode: Int) {
        addCmdToQueue(byteArrayOf(cmdFrameHead[0], cmdFrameHead[1], cmdCode.toByte()))
    }

    fun forceStopCar() {
        if (!hasPermissions()) return
        val stopCmd = byteArrayOf(cmdFrameHead[0], cmdFrameHead[1], CMD_CAR_MOVE.toByte(), 0, 0)
        controlCharacteristic?.let { char ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(char, stopCmd, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.value = stopCmd
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                bluetoothGatt?.writeCharacteristic(char)
            }
        }
    }

    private fun addCmdToQueue(cmd: ByteArray) {
        if (!isConnected || controlCharacteristic == null) return
        bleCmdQueue.offer(cmd)
        processNextCmd()
    }

    private fun processNextCmd() {
        synchronized(cmdLock) {
            if (isSendingCmd || bleCmdQueue.isEmpty()) return
            isSendingCmd = true
            val cmd = bleCmdQueue.poll() ?: return
            writeBleChar(cmd)
            bleHandler.postDelayed({
                isSendingCmd = false
                processNextCmd()
            }, cmdSendIntervalMs)
        }
    }

    private fun writeBleChar(data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = controlCharacteristic ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (_: Exception) {}
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            isConnecting = false
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                callback.onConnectionStateChanged(isConnected = true, isConnecting = false, tip = context.getString(R.string.ble_tip_connect_success))
                gatt?.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt?.discoverServices()
                startReadRssiLoop()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                controlCharacteristic = null
                videoCharacteristic = null
                deviceStatusCharacteristic = null
                closeGatt()
                callback.onConnectionStateChanged(isConnected = false, isConnecting = false, tip = context.getString(R.string.ble_tip_disconnected))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt?.getService(bleServiceUuid) ?: return
            controlCharacteristic = service.getCharacteristic(controlCharUuid)
            videoCharacteristic = service.getCharacteristic(videoCharUuid)
            deviceStatusCharacteristic = service.getCharacteristic(statusCharUuid)
            gatt.requestMtu(512)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            openVideoNotify(gatt)
            if (deviceStatusCharacteristic != null) {
                bleHandler.postDelayed({ bluetoothGatt?.readCharacteristic(deviceStatusCharacteristic) }, 200)
                startReadDeviceStatusLoop()
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, c: BluetoothGattCharacteristic?) {
            if (c?.uuid == videoCharUuid) {
                @Suppress("DEPRECATION")
                c.value?.let { callback.onVideoDataReceived(it.copyOf(it.size)) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == videoCharUuid) {
                callback.onVideoDataReceived(value.copyOf(value.size))
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt?, c: BluetoothGattCharacteristic?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && c?.uuid == statusCharUuid) {
                @Suppress("DEPRECATION")
                val data = c.value ?: return
                if (data.isNotEmpty()) {
                    val bright = data[0].toInt() and 0xFF
                    val temp = if (data.size >= 2) data[1].toInt() and 0xFF else -1
                    callback.onStatusDataRead(bright, temp)
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && c.uuid == statusCharUuid) {
                if (value.isNotEmpty()) {
                    val bright = value[0].toInt() and 0xFF
                    val temp = if (value.size >= 2) value[1].toInt() and 0xFF else -1
                    callback.onStatusDataRead(bright, temp)
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) callback.onRssiUpdated(rssi)
        }
    }

    private fun openVideoNotify(gatt: BluetoothGatt?) {
        val char = videoCharacteristic ?: return
        try {
            gatt?.setCharacteristicNotification(char, true)
            val desc = char.getDescriptor(clientCharConfigUuid)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt?.writeDescriptor(desc)
            }
        } catch (_: Exception) {}
    }

    private fun startReadRssiLoop() {
        if (!isConnected || bluetoothGatt == null) return
        try { bluetoothGatt?.readRemoteRssi() } catch (_: Exception) {}
        bleHandler.postDelayed({ startReadRssiLoop() }, rssiReadIntervalMs)
    }

    private fun startReadDeviceStatusLoop() {
        if (!isConnected || deviceStatusCharacteristic == null) return
        bluetoothGatt?.readCharacteristic(deviceStatusCharacteristic)
        bleHandler.postDelayed({ startReadDeviceStatusLoop() }, statusReadPeriodMs)
    }
}