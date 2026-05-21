/*
 * ESPAUTO
 * Copyright (c) 2026 honoz
 * Licensed under the MIT License.
 */

package com.android.espauto

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
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
        fun onScanFinished()
    }

    // 符合 Bluetooth Core Specification 标准的 128 位特异性业务识别码
    private val CLIENT_CHAR_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val BLE_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val VIDEO_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CONTROL_CHAR_UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
    private val STATUS_CHAR_UUID = UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb")

    // 下位机硬件私有帧首部魔数与核心功能映射控制码
    private val CMD_FRAME_HEAD = byteArrayOf(0xAA.toByte(), 0xFF.toByte())
    val CMD_LED_BRIGHTNESS = 0x00
    val CMD_BEEP = 0x12
    val CMD_SERVO_UP = 0x13
    val CMD_SERVO_DOWN = 0x14
    val CMD_CAR_MOVE = 0x15

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

    // 无锁高并发队列：用于缓冲UI高频下发的控制帧，避免多线程写入时竞争卡死
    private val bleCmdQueue = ConcurrentLinkedQueue<ByteArray>()
    private val bleHandler = Handler(Looper.getMainLooper())
    private var isSendingCmd = false
    private val cmdLock = Any()

    // 硬件时序约束常量：规避小车 MCU 蓝牙芯片因高频吞吐导致的物理层丢包
    private val CMD_SEND_INTERVAL_MS = 10L
    private val RSSI_READ_INTERVAL_MS = 1000L
    private val STATUS_READ_PERIOD_MS = 5000L

    fun hasPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    fun startScan() {
        if (!hasPermissions() || bluetoothAdapter?.isEnabled != true || isScanning) return
        isScanning = true
        scanDeviceList.clear()
        bleScanner = bluetoothAdapter.bluetoothLeScanner
        // 激活高频低延时扫描，确保能第一时间捕获小车外设发出的广播信标
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(null, settings, scanCallback)

        // 强设定时器：5秒后自动熔断扫描逻辑，减缓手机蓝牙射频芯片的硬件耗电量
        bleHandler.postDelayed({ stopScan(); callback.onScanFinished() }, 5000)
    }

    fun stopScan() {
        if (!hasPermissions() || !isScanning) return
        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
    }

    private val scanDeviceList = mutableListOf<BluetoothDevice>()
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val record = result.scanRecord ?: return
            // 严格的指纹空滤：只有设备广播字段中明确声明携带了本小车专属的服务 UUID，才允许上报给主界面
            val hasTargetService = record.serviceUuids?.any { it.uuid == BLE_SERVICE_UUID } == true
            if (!scanDeviceList.contains(dev) && hasTargetService) {
                scanDeviceList.add(dev)
                callback.onDeviceFound(dev)
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        if (!hasPermissions() || isConnecting || isConnected) return
        isConnecting = true
        closeGatt()
        // 绑定底层的 TRANSPORT_LE 模式启动非对称的 GATT 拓扑网络连接
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
        addCmdToQueue(byteArrayOf(CMD_FRAME_HEAD[0], CMD_FRAME_HEAD[1], CMD_CAR_MOVE.toByte(), speed.toByte(), steer.toByte()))
    }

    fun sendLedBrightCmd(bright: Int) {
        addCmdToQueue(byteArrayOf(CMD_FRAME_HEAD[0], CMD_FRAME_HEAD[1], CMD_LED_BRIGHTNESS.toByte(), bright.toByte()))
    }

    fun sendSimpleControlCmd(cmdCode: Int) {
        addCmdToQueue(byteArrayOf(CMD_FRAME_HEAD[0], CMD_FRAME_HEAD[1], cmdCode.toByte()))
    }

    fun forceStopCar() {
        if (!hasPermissions()) return
        val stopCmd = byteArrayOf(CMD_FRAME_HEAD[0], CMD_FRAME_HEAD[1], CMD_CAR_MOVE.toByte(), 0, 0)
        controlCharacteristic?.let { char ->
            char.value = stopCmd
            // 采用 WRITE_TYPE_NO_RESPONSE（不带握手回执的快速写入模式），将急刹指令在极短时间内倾泻出去
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            bluetoothGatt?.writeCharacteristic(char)
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
            // 强制引入物理信道发包间隔（10ms 盲区窗），给车载低端芯片提供充足的软硬件中断解析间隙
            bleHandler.postDelayed({
                isSendingCmd = false
                processNextCmd()
            }, CMD_SEND_INTERVAL_MS)
        }
    }

    private fun writeBleChar(data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = controlCharacteristic ?: return
        try {
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(char)
        } catch (_: Exception) {}
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            isConnecting = false
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                callback.onConnectionStateChanged(true, false, context.getString(R.string.ble_tip_connect_success))
                // 性能调优：强制切换成高带宽的 LE 2M PHY 射频调制模式，保障大体积视频帧能获得足够的吞吐空间
                gatt?.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                // 性能调优：提升底层连接频次优先级，将 Connection Interval 压缩到极限，降低遥控延迟
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt?.discoverServices()
                startReadRssiLoop()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                controlCharacteristic = null
                videoCharacteristic = null
                deviceStatusCharacteristic = null
                closeGatt()
                callback.onConnectionStateChanged(false, false, context.getString(R.string.ble_tip_disconnected))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt?.getService(BLE_SERVICE_UUID)
            controlCharacteristic = service?.getCharacteristic(CONTROL_CHAR_UUID)
            videoCharacteristic = service?.getCharacteristic(VIDEO_CHAR_UUID)
            deviceStatusCharacteristic = service?.getCharacteristic(STATUS_CHAR_UUID)
            // 性能调优：扩容最大传输单元 (MTU) 到 512 字节，解除蓝牙 23 字节的默认报文封锁，合并高频碎片包
            gatt?.requestMtu(512)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            // 在 MTU 成功扩大后，激活图传特征值订阅通知，通知车载主控开始泵入高速图传图像
            openVideoNotify(gatt)
            bleHandler.postDelayed({ bluetoothGatt?.readCharacteristic(deviceStatusCharacteristic) }, 200)
            startReadDeviceStatusLoop()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, c: BluetoothGattCharacteristic?) {
            // 被动数据接收中心：当小车向 VIDEO 特征值推送图像数据包时，该回调捕获负载并直接交由图传解析器
            if (c?.uuid == VIDEO_CHAR_UUID) {
                c.value?.let { callback.onVideoDataReceived(it) }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, c: BluetoothGattCharacteristic?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && c?.uuid == STATUS_CHAR_UUID) {
                val data = c.value ?: return
                if (data.isNotEmpty()) {
                    // 解包硬件上报协议：第 0 字节映射车灯亮度，第 1 字节二进制无符号映射车载核心板温度
                    val bright = data[0].toInt() and 0xFF
                    val temp = if (data.size >= 2) data[1].toInt() and 0xFF else -1
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
            // 协议级订阅要求：必须手动改写特征值下属的 2902 描述符，使能 Client Characteristic Configuration (CCCD) 的 Notification 标志
            val desc = char.getDescriptor(CLIENT_CHAR_CONFIG_UUID)
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt?.writeDescriptor(desc)
        } catch (_: Exception) {}
    }

    private fun startReadRssiLoop() {
        if (!isConnected || bluetoothGatt == null) return
        try { bluetoothGatt?.readRemoteRssi() } catch (_: Exception) {}
        bleHandler.postDelayed({ startReadRssiLoop() }, RSSI_READ_INTERVAL_MS)
    }

    private fun startReadDeviceStatusLoop() {
        if (!isConnected || deviceStatusCharacteristic == null) return
        bluetoothGatt?.readCharacteristic(deviceStatusCharacteristic)
        bleHandler.postDelayed({ startReadDeviceStatusLoop() }, STATUS_READ_PERIOD_MS)
    }
}