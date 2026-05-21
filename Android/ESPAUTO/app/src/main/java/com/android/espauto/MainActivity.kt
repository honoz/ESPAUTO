/*
 * ESPAUTO
 * Copyright (c) 2026 honoz
 * Licensed under the MIT License.
 */

package com.android.espauto

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.espauto.databinding.ActivityMainBinding
import com.android.espauto.databinding.DialogDeviceListBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : AppCompatActivity(), BleManager.BleCallback, VideoFrameParser.FrameParseListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var frameParser: VideoFrameParser
    private lateinit var videoRecorder: VideoRecorder

    private var targetDevice: BluetoothDevice? = null
    // 跨线程内存可见性物标：加 Volatile 确保解码线程与硬件录制线程访问当前 Bitmap 指针时拥有绝对的最新视图
    @Volatile private var currentBitmapFrame: Bitmap? = null

    private var isManualDisconnect = false
    private var reconnectCount = 0
    private val MAX_RECONNECT_TIMES = 1
    private var allowAutoShowScanDialog = true

    private var isServoUpLongPress = false
    private var isServoDownLongPress = false
    private val servoHandler = Handler(Looper.getMainLooper())

    private var isGyroControlMode = false
    private var sensorManager: SensorManager? = null
    private var accelerometerSensor: Sensor? = null
    private var lastGyroTime = 0L
    private val GYRO_INTERVAL = 120L

    private var recordStartTime = 0L
    private val recordTimerHandler = Handler(Looper.getMainLooper())
    private var recordTimerRunnable: Runnable? = null

    private val scanDeviceList = mutableListOf<BluetoothDevice>()
    private lateinit var deviceListAdapter: DeviceListAdapter
    private var bottomScanDialog: BottomSheetDialog? = null

    // 状态机定义：严格控制系统由于网络跃迁带来的视图形态不确定性切换
    private enum class UiState { LOADING, NORMAL, IDLE, RECONNECT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = BleManager(this, this)
        frameParser = VideoFrameParser(this)
        videoRecorder = VideoRecorder(this)

        initViews()
        initSensors()

        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        executeBluetoothFlow()
    }

    private fun initViews() {
        deviceListAdapter = DeviceListAdapter()

        binding.btnModeSwitch.setOnClickListener {
            isGyroControlMode = !isGyroControlMode
            if (isGyroControlMode) {
                binding.btnModeSwitch.setImageResource(R.drawable.ic_portrait_on)
                ToastUtil.show(this, getString(R.string.toast_gyro_on))
                toggleSensor(true)
            } else {
                binding.btnModeSwitch.setImageResource(R.drawable.ic_portrait_off)
                ToastUtil.show(this, getString(R.string.toast_gyro_off))
                toggleSensor(false)
                bleManager.forceStopCar()
            }
        }

        binding.ivReconnectIcon.setOnClickListener {
            if (!bleManager.isConnected && !bleManager.isConnecting && targetDevice != null) {
                reconnectCount = 1
                isManualDisconnect = false
                setUiState(UiState.LOADING, getString(R.string.tip_connecting_device))
                bleManager.connect(targetDevice!!)
            }
        }

        binding.sliderLedBrightness.addOnChangeListener { _, value, _ ->
            bleManager.sendLedBrightCmd(value.toInt())
            // 物理形态拟真交互：随着调光数值的增大，将灯光图标进行线性旋转来映射亮度阀值
            binding.ivLedIcon.rotation = (value / binding.sliderLedBrightness.valueTo) * 90f
        }

        binding.btnDeviceBeep.setOnClickListener { bleManager.sendSimpleControlCmd(bleManager.CMD_BEEP) }

        setupServoTouchEvents()

        binding.btnScreenshot.setOnClickListener { saveScreenshot() }
        binding.btnVideoRecord.setOnClickListener { toggleRecording() }

        binding.btnScanDevice.setOnClickListener {
            allowAutoShowScanDialog = false
            isManualDisconnect = true
            if (bleManager.isConnected || bleManager.isConnecting) bleManager.disconnect()
            if (bleManager.bluetoothAdapter?.isEnabled == true) showDeviceScanDialog()
        }

        initTouchPad()
    }

    private fun initTouchPad() {
        binding.touchPadControl.listener = object : TouchPadView.OnTouchPadMoveListener {
            override fun onMove(x: Int, y: Int) {
                if (isGyroControlMode) return
                // 极坐标系换算：将手柄 Y 轴直接对齐电调马达速度（Speed），X 轴取反对齐舵机差速转向（Steer）
                bleManager.sendCarMoveCmd(y, -x)
            }
            override fun onStop() {
                if (isGyroControlMode) return
                servoHandler.removeCallbacksAndMessages(null)
                bleManager.forceStopCar()
            }
        }
    }

    private fun executeBluetoothFlow() {
        if (bleManager.hasPermissions()) {
            checkAndTriggerBluetooth()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1001)
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkAndTriggerBluetooth() {
        if (bleManager.bluetoothAdapter?.isEnabled == true) {
            autoConnectOrScan()
        } else {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 1002)
        }
    }

    private fun autoConnectOrScan() {
        // 读取轻量级持久化配置：尝试抓取历史成功配对过的 MAC 地址实现无感二次自动回连
        val savedAddr = getSharedPreferences("ESPAUTO_CONFIG", MODE_PRIVATE).getString("saved_device_address", null)
        if (!savedAddr.isNullOrEmpty()) {
            try {
                val dev = bleManager.bluetoothAdapter?.getRemoteDevice(savedAddr)
                if (dev != null) {
                    setUiState(UiState.LOADING, getString(R.string.tip_connecting_device))
                    targetDevice = dev
                    bleManager.connect(dev)
                    return
                }
            } catch (_: Exception) {}
        }
        if (allowAutoShowScanDialog) {
            allowAutoShowScanDialog = false
            Handler(Looper.getMainLooper()).postDelayed({ showDeviceScanDialog() }, 500)
        }
    }

    override fun onConnectionStateChanged(isConnected: Boolean, isConnecting: Boolean, tip: String) {
        if (isConnected) {
            isManualDisconnect = false
            reconnectCount = 0
            clearVideoDisplay()
            setUiState(UiState.LOADING, tip)
            targetDevice?.address?.let {
                getSharedPreferences("ESPAUTO_CONFIG", MODE_PRIVATE).edit().putString("saved_device_address", it).apply()
            }
            // 链路安全守卫：若建立连接 5 秒后，核心图传管道依然没有抛出任何合法的解析帧，判定信号假在线，强转空闲视图
            Handler(Looper.getMainLooper()).postDelayed({
                if (bleManager.isConnected && currentBitmapFrame == null) {
                    setUiState(UiState.IDLE, getString(R.string.tip_no_video_stream))
                }
            }, 5000)
        } else {
            clearVideoDisplay()
            if (videoRecorder.isRecording) stopRecordingTimer()
            bleManager.closeGatt()

            if (isManualDisconnect) {
                setUiState(UiState.IDLE, getString(R.string.tip_waiting_connection))
            } else if (reconnectCount < MAX_RECONNECT_TIMES && targetDevice != null) {
                // 链路抖动自愈机制：在非人为断开的异常宕线情况下，允许启动单次自愈性重新连线
                reconnectCount++
                setUiState(UiState.LOADING, getString(R.string.tip_reconnecting))
                Handler(Looper.getMainLooper()).postDelayed({ targetDevice?.let { bleManager.connect(it) } }, 1500)
            } else {
                setUiState(UiState.RECONNECT, getString(R.string.tip_reconnect_failed))
            }
        }
    }

    override fun onVideoDataReceived(data: ByteArray) {
        frameParser.onRawDataReceived(data)
    }

    override fun onStatusDataRead(brightness: Int, temperature: Int) {
        // 数据流重绑定：由于 BLE 回调身处异步子线程，所有的 UI 组件重绘赋值必须强制切回系统的 Main Looper 环
        runOnUiThread {
            binding.sliderLedBrightness.value = brightness.toFloat()
            binding.tvTemperature.text = "${temperature}℃"
        }
    }

    override fun onRssiUpdated(rssi: Int) {
        runOnUiThread {
            // 利用射频衰减分贝模型（dBm），以 [-100, -50] 差值法进行非线性百分比拟合，动态折算成顶部的信号阶梯图标
            val percent = when { rssi >= -50 -> 100; rssi <= -100 -> 0; else -> 100 + ((rssi + 50) * 2) }
            val icon = when {
                rssi >= -50 -> R.drawable.ic_signal_4_bar
                rssi >= -70 -> R.drawable.ic_signal_3_bar
                rssi >= -85 -> R.drawable.ic_signal_2_bar
                rssi >= -100 -> R.drawable.ic_signal_1_bar
                else -> R.drawable.ic_signal_0_bar
            }
            val draw = ContextCompat.getDrawable(this, icon)
            draw?.setBounds(0, 0, draw.intrinsicWidth, draw.intrinsicHeight)
            binding.tvSignalStrength.setCompoundDrawables(draw, null, null, null)
            binding.tvSignalStrength.text = "$percent%"
        }
    }

    override fun onDeviceFound(device: BluetoothDevice) {
        runOnUiThread {
            if (!scanDeviceList.contains(device)) {
                scanDeviceList.add(device)
                deviceListAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onScanFinished() {
        runOnUiThread {
            if (scanDeviceList.isEmpty()) {
                ToastUtil.show(this, getString(R.string.toast_no_device_scanned))
                bottomScanDialog?.dismiss()
            }
        }
    }

    override fun onFrameParsed(bitmap: Bitmap, fps: Int) {
        // 利用临界区互斥锁保护当前持有帧的指针修改，确保多线程并行时不会引发空指针引用异常
        synchronized(this) {
            currentBitmapFrame = bitmap
        }

        if (videoRecorder.isRecording) {
            videoRecorder.feedFrame(bitmap)
        }

        runOnUiThread {
            setUiState(UiState.NORMAL)
            binding.ivVideoDisplay.setImageBitmap(null)
            // 将最新解出的位图数据泵入图像视图的硬件渲染图层
            binding.ivVideoDisplay.setImageBitmap(bitmap)
            binding.tvVideoFps.text = "${fps}FPS"
        }
    }

    private fun toggleRecording() {
        if (videoRecorder.isRecording) {
            videoRecorder.stop()
            stopRecordingTimer()
            updateStatusLabel(getString(R.string.status_live), R.drawable.ic_live)
            binding.btnVideoRecord.setImageResource(R.drawable.ic_video)
            ToastUtil.show(this, getString(R.string.toast_video_recorded))
        } else {
            val sample = currentBitmapFrame
            if (sample == null || sample.isRecycled) {
                ToastUtil.show(this, getString(R.string.toast_no_live_frame))
                return
            }
            // 利用当前捕获的首帧宽高完成物理编码轨道的初始化，开辟录制录像管线
            if (videoRecorder.start(sample)) {
                startRecordingTimer()
                binding.btnVideoRecord.setImageResource(R.drawable.ic_stop)
                ToastUtil.show(this, getString(R.string.toast_video_started))
            }
        }
    }

    private fun saveScreenshot() {
        val bmp = currentBitmapFrame
        if (bmp == null || bmp.isRecycled) {
            ToastUtil.show(this, getString(R.string.toast_no_live_frame))
            return
        }
        val copy = bmp.copy(Bitmap.Config.RGB_565, false) ?: return
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ESPAUTO")
        }
        try {
            // 通过系统的 ContentResolver 插入一条公共媒体库记号，获取受保护的物理 Uri 写入流
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
            contentResolver.openOutputStream(uri)?.use { copy.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ToastUtil.show(this, getString(R.string.toast_screenshot_success))
        } catch (_: Exception) {
            ToastUtil.show(this, getString(R.string.toast_screenshot_failed))
        } finally {
            copy.recycle()
        }
    }

    private fun setUiState(state: UiState, tip: String = "") {
        runOnUiThread {
            binding.rlLoadingContainer.visibility = if (state != UiState.NORMAL) View.VISIBLE else View.GONE
            binding.llLiveStatusLabel.visibility = if (state == UiState.NORMAL) View.VISIBLE else View.GONE
            binding.llDeviceInfoPanel.visibility = if (state == UiState.NORMAL) View.VISIBLE else View.GONE
            binding.ivNoVideoIcon.visibility = if (state == UiState.IDLE) View.VISIBLE else View.GONE
            binding.ivReconnectIcon.visibility = if (state == UiState.RECONNECT) View.VISIBLE else View.GONE
            binding.pbLoadingIndicator.visibility = if (state == UiState.LOADING) View.VISIBLE else View.GONE
            binding.tvLoadingTip.text = tip
        }
    }

    private fun clearVideoDisplay() {
        frameParser.clearBuffer()
        runOnUiThread { binding.ivVideoDisplay.setImageDrawable(null) }
        synchronized(this) {
            currentBitmapFrame?.recycle()
            currentBitmapFrame = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupServoTouchEvents() {
        // 利用 Handler 内部的闭包自循环机制，构建高精度的短周期轮询器（80ms），从而完美模拟硬件级的持续长按逻辑
        val loopRun = object : Runnable {
            override fun run() {
                if (isServoUpLongPress) {
                    bleManager.sendSimpleControlCmd(bleManager.CMD_SERVO_UP)
                    servoHandler.postDelayed(this, 80)
                } else if (isServoDownLongPress) {
                    bleManager.sendSimpleControlCmd(bleManager.CMD_SERVO_DOWN)
                    servoHandler.postDelayed(this, 80)
                }
            }
        }

        binding.btnServoUp.setOnClickListener { bleManager.sendSimpleControlCmd(bleManager.CMD_SERVO_UP) }
        binding.btnServoUp.setOnLongClickListener { isServoUpLongPress = true; servoHandler.post(loopRun); true }
        binding.btnServoUp.setOnTouchListener { _, event ->
            // 触控边界保护：一旦手指离开弹起或动作被打断，强制抹除定时器标记，终止连续长按发包
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                isServoUpLongPress = false; servoHandler.removeCallbacksAndMessages(null)
            }
            false
        }

        binding.btnServoDown.setOnClickListener { bleManager.sendSimpleControlCmd(bleManager.CMD_SERVO_DOWN) }
        binding.btnServoDown.setOnLongClickListener { isServoDownLongPress = true; servoHandler.post(loopRun); true }
        binding.btnServoDown.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                isServoDownLongPress = false; servoHandler.removeCallbacksAndMessages(null)
            }
            false
        }
    }

    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun toggleSensor(enable: Boolean) {
        if (enable) {
            if (accelerometerSensor == null) {
                ToastUtil.show(this, getString(R.string.toast_no_gyro_sensor))
                isGyroControlMode = false
                return
            }
            // 采用 SENSOR_DELAY_GAME（适合游戏级的超高频物理采样频率）注册车载硬件重力传感器
            sensorManager?.registerListener(sensorListener, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensorManager?.unregisterListener(sensorListener)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (!isGyroControlMode || !bleManager.isConnected || event == null) return
            // 引入软件高频防抖：利用时间戳节流阀（120ms），强行过滤传感器过于灵敏的物理轻微震颤
            val now = System.currentTimeMillis()
            if (now - lastGyroTime < GYRO_INTERVAL) return
            lastGyroTime = now

            val x = event.values[0]
            val y = event.values[1]
            // 加权放大模型：将重力感应物理分量 [-9.8, 9.8] 乘以增益系数，将其强行折算并限幅在下位机契合的 [-100, 100] 控制区间内
            var speed = (y * 12).toInt().coerceIn(-100, 100)
            var steer = (x * 12).toInt().coerceIn(-100, 100)
            // 软件死区（Dead-zone）隔离：当重力倾角在绝对零点上下 9% 范围内波动时判定为操作者手抖，强行归零防止原地打滑
            if (speed in -9..9) speed = 0
            if (steer in -9..9) steer = 0

            bleManager.sendCarMoveCmd(speed, steer)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startRecordingTimer() {
        recordStartTime = System.currentTimeMillis()
        recordTimerRunnable = object : Runnable {
            override fun run() {
                val dur = (System.currentTimeMillis() - recordStartTime) / 1000
                val timeStr = String.format("%02d:%02d", dur / 60, dur % 60)
                updateStatusLabel(getString(R.string.status_record, timeStr), R.drawable.ic_record)
                recordTimerHandler.postDelayed(this, 1000)
            }
        }
        recordTimerRunnable?.let { recordTimerHandler.post(it) }
    }

    private fun stopRecordingTimer() {
        recordTimerRunnable?.let { recordTimerHandler.removeCallbacks(it) }
        recordTimerRunnable = null
    }

    private fun updateStatusLabel(text: String, @DrawableRes icon: Int) {
        runOnUiThread {
            binding.tvLiveStatusText.text = text
            binding.ivLiveStatusIcon.setImageResource(icon)
        }
    }

    private fun showDeviceScanDialog() {
        if (bottomScanDialog?.isShowing == true) return
        bleManager.stopScan()
        scanDeviceList.clear()
        deviceListAdapter.notifyDataSetChanged()

        val dialogBinding = DialogDeviceListBinding.inflate(layoutInflater)
        // 采用 Material Design 的底层抽屉视窗（BottomSheetDialog）作为低功耗蓝牙设备的扫描呈现交互体
        bottomScanDialog = BottomSheetDialog(this, R.style.Theme_App_BottomSheetDialog).apply {
            setContentView(dialogBinding.root)
            setCanceledOnTouchOutside(false)
        }
        dialogBinding.rvDevices.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvDevices.adapter = deviceListAdapter
        dialogBinding.tvTip.text = getString(R.string.tip_scanning_device)
        bottomScanDialog?.show()

        bleManager.startScan()
    }

    private inner class DeviceListAdapter : RecyclerView.Adapter<DeviceViewHolder>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = DeviceViewHolder(layoutInflater.inflate(R.layout.device_list_item, p, false))
        override fun getItemCount() = scanDeviceList.size
        @SuppressLint("MissingPermission")
        override fun onBindViewHolder(h: DeviceViewHolder, p: Int) {
            val dev = scanDeviceList[p]
            h.tvName.text = dev.name ?: getString(R.string.device_unknown)
            h.tvAddr.text = dev.address
            h.itemView.setOnClickListener {
                bleManager.stopScan()
                // 重置自动化回连的首选项：当用户发生了主动点击行为，立刻擦除历史绑定的 MAC，锁定新选择的蓝牙目标
                getSharedPreferences("ESPAUTO_CONFIG", MODE_PRIVATE).edit().remove("saved_device_address").apply()
                bottomScanDialog?.dismiss()
                targetDevice = dev
                setUiState(UiState.LOADING, getString(R.string.tip_connecting_target_device, dev.name ?: getString(R.string.device_unknown)))
                bleManager.connect(dev)
            }
        }
    }

    private class DeviceViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(android.R.id.text1)
        val tvAddr: TextView = v.findViewById(android.R.id.text2)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, p, gr)
        if (rc == 1001) {
            if (bleManager.hasPermissions()) {
                checkAndTriggerBluetooth()
            } else {
                ToastUtil.show(this, getString(R.string.toast_bluetooth_permission_denied))
                finish()
            }
        }
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc == 1002 && res == RESULT_OK) autoConnectOrScan()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        ToastUtil.show(this@MainActivity, getString(R.string.toast_bluetooth_turned_off))
                        isManualDisconnect = true
                        bleManager.disconnect()
                        setUiState(UiState.IDLE, getString(R.string.tip_bluetooth_off))
                    }
                    BluetoothAdapter.STATE_ON -> executeBluetoothFlow()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 退出安全熔断：彻底注销所有底层观察者与传感器监听器，释放软硬件锁，确保 Activity 销毁后无孤立死锁线程
        unregisterReceiver(bluetoothReceiver)
        toggleSensor(false)
        stopRecordingTimer()
        videoRecorder.stop()
        frameParser.release()
        bleManager.disconnect()

        synchronized(this) {
            currentBitmapFrame?.recycle()
            currentBitmapFrame = null
        }
    }
}