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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.espauto.databinding.ActivityMainBinding
import kotlin.math.abs

class MainActivity : AppCompatActivity(), BleManager.BleCallback,
    VideoFrameParser.FrameParseListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var frameParser: VideoFrameParser
    private lateinit var videoRecorder: VideoRecorder

    private var targetDevice: BluetoothDevice? = null
    @Volatile
    private var currentBitmapFrame: Bitmap? = null

    private var isManualDisconnect = false
    private var reconnectCount = 0
    private val maxReconnectTimes = 1

    private var isServoUpLongPress = false
    private var isServoDownLongPress = false
    private val servoHandler = Handler(Looper.getMainLooper())

    private var isGravityMode = false

    private var recordStartTime = 0L
    private val recordTimerHandler = Handler(Looper.getMainLooper())
    private var recordTimerRunnable: Runnable? = null

    private val scanDeviceList = mutableListOf<BluetoothDevice>()
    private lateinit var deviceListAdapter: DeviceListAdapter

    private var currentUiState: UiState = UiState.IDLE

    private enum class UiState { LOADING, NORMAL, IDLE, RECONNECT, SCANNING }

    companion object {
        private const val GRAVITY = SensorManager.STANDARD_GRAVITY
        private const val GRAVITY_DEAD_ZONE = 0.15f
        private const val GRAVITY_MAX_SPEED = 127
        private const val GRAVITY_MAX_STEER = 100
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val gravitySensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || !isGravityMode || !bleManager.isConnected) return

            val pitchNorm = (event.values[1] / GRAVITY).coerceIn(-1f, 1f)
            val rollNorm = (event.values[0] / GRAVITY).coerceIn(-1f, 1f)

            val speed = if (abs(pitchNorm) < GRAVITY_DEAD_ZONE) 0
            else (pitchNorm * GRAVITY_MAX_SPEED).toInt()
                .coerceIn(-GRAVITY_MAX_SPEED, GRAVITY_MAX_SPEED)

            val steer = if (abs(rollNorm) < GRAVITY_DEAD_ZONE) 0
            else (rollNorm * GRAVITY_MAX_STEER).toInt()
                .coerceIn(-GRAVITY_MAX_STEER, GRAVITY_MAX_STEER)

            bleManager.sendCarMoveCmd(speed, steer)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = BleManager(this, this)
        frameParser = VideoFrameParser(this)
        videoRecorder = VideoRecorder(this)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        initViews()

        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        executeBluetoothFlow()
    }

    private fun initViews() {
        deviceListAdapter = DeviceListAdapter()
        binding.rvScanDevices.layoutManager = LinearLayoutManager(this)
        binding.rvScanDevices.adapter = deviceListAdapter

        val skeletonAnim =
            android.view.animation.AnimationUtils.loadAnimation(this, R.anim.anim_skeleton_pulse)
        binding.llSkeletonContainer.startAnimation(skeletonAnim)

        binding.btnModeSwitch.setOnClickListener {
            if (!bleManager.isConnected) {
                ToastUtil.show(this, getString(R.string.toast_no_connection_mode))
                return@setOnClickListener
            }
            isGravityMode = !isGravityMode
            if (isGravityMode) {
                accelerometer?.let {
                    sensorManager.registerListener(
                        gravitySensorListener,
                        it,
                        SensorManager.SENSOR_DELAY_GAME
                    )
                }
                binding.btnModeSwitch.setImageResource(R.drawable.ic_gravity_on)
                ToastUtil.show(this, getString(R.string.toast_gravity_on))
            } else {
                sensorManager.unregisterListener(gravitySensorListener)
                binding.btnModeSwitch.setImageResource(R.drawable.ic_gravity_off)
                ToastUtil.show(this, getString(R.string.toast_gravity_off))
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
            binding.ivLedIcon.rotation = (value / binding.sliderLedBrightness.valueTo) * 90f
        }

        binding.btnDeviceBeep.setOnClickListener {
            if (!bleManager.isConnected) {
                ToastUtil.show(this, getString(R.string.toast_no_connection_beep))
                return@setOnClickListener
            }
            bleManager.sendSimpleControlCmd(BleManager.CMD_BEEP)
            ToastUtil.show(this, getString(R.string.toast_beep_sent))
        }

        setupServoTouchEvents()

        binding.btnScreenshot.setOnClickListener { saveScreenshot() }
        binding.btnVideoRecord.setOnClickListener { toggleRecording() }

        binding.btnScanDevice.setOnClickListener {
            isManualDisconnect = true
            if (bleManager.isConnected || bleManager.isConnecting) bleManager.disconnect()
            if (bleManager.bluetoothAdapter?.isEnabled == true) startDeviceScan()
        }

        initTouchPad()
    }

    private fun initTouchPad() {
        binding.touchPadControl.listener = object : TouchPadView.OnTouchPadMoveListener {
            override fun onMove(x: Int, y: Int) {
                if (isGravityMode) return
                bleManager.sendCarMoveCmd(y, -x)
            }

            override fun onStop() {
                if (isGravityMode) return
                servoHandler.removeCallbacksAndMessages(null)
                bleManager.forceStopCar()
            }
        }
    }

    private fun executeBluetoothFlow() {
        if (bleManager.hasPermissions()) {
            checkAndTriggerBluetooth()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                1001
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkAndTriggerBluetooth() {
        if (bleManager.bluetoothAdapter?.isEnabled == true) {
            autoConnectOrScan()
        } else {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    private fun autoConnectOrScan() {
        val savedAddr = getSharedPreferences("ESPAUTO_CONFIG", MODE_PRIVATE).getString(
            "saved_device_address",
            null
        )
        if (!savedAddr.isNullOrEmpty()) {
            try {
                val dev = bleManager.bluetoothAdapter?.getRemoteDevice(savedAddr)
                if (dev != null) {
                    setUiState(UiState.LOADING, getString(R.string.tip_connecting_device))
                    targetDevice = dev
                    bleManager.connect(dev)
                    return
                }
            } catch (_: Exception) {
            }
        }
        startDeviceScan()
    }

    private fun startDeviceScan() {
        if (currentUiState == UiState.SCANNING && bleManager.isScanning) return
        val oldSize = scanDeviceList.size
        scanDeviceList.clear()
        deviceListAdapter.notifyItemRangeRemoved(0, oldSize)
        binding.pbScanIndicator.visibility = View.VISIBLE
        binding.tvScanTip.text = getString(R.string.tip_scanning_device)
        binding.llSkeletonContainer.visibility = View.VISIBLE
        binding.llSkeletonContainer.startAnimation(
            android.view.animation.AnimationUtils.loadAnimation(this, R.anim.anim_skeleton_pulse)
        )
        setUiState(UiState.SCANNING)
        if (bleManager.isScanning) bleManager.stopScan(cancel = false)
        bleManager.startScan()
    }

    private fun stopDeviceScan(tip: String = getString(R.string.tip_scan_no_device)) {
        bleManager.stopScan()
        scanDeviceList.clear()
        setUiState(UiState.IDLE, tip)
    }

    override fun onConnectionStateChanged(
        isConnected: Boolean,
        isConnecting: Boolean,
        tip: String
    ) {
        if (isConnected) {
            isManualDisconnect = false
            reconnectCount = 0
            clearVideoDisplay()
            setUiState(UiState.LOADING, tip)
            targetDevice?.address?.let { addr ->
                getSharedPreferences("ESPAUTO_CONFIG", MODE_PRIVATE).edit {
                    putString("saved_device_address", addr)
                }
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (bleManager.isConnected && currentBitmapFrame == null) {
                    setUiState(UiState.IDLE, getString(R.string.tip_no_video_stream))
                }
            }, 5000)
        } else {
            bleManager.closeGatt()
            if (isGravityMode) {
                isGravityMode = false
                sensorManager.unregisterListener(gravitySensorListener)
                binding.btnModeSwitch.setImageResource(R.drawable.ic_gravity_off)
            }
            if (videoRecorder.isRecording) stopRecordingTimer()
            clearVideoDisplay()

            if (currentUiState == UiState.SCANNING) return

            if (isManualDisconnect) {
                setUiState(UiState.IDLE, getString(R.string.tip_scan_no_device))
            } else if (reconnectCount < maxReconnectTimes && targetDevice != null) {
                reconnectCount++
                setUiState(UiState.LOADING, getString(R.string.tip_reconnecting))
                Handler(Looper.getMainLooper()).postDelayed({
                    targetDevice?.let {
                        bleManager.connect(
                            it
                        )
                    }
                }, 1500)
            } else {
                setUiState(UiState.RECONNECT, getString(R.string.tip_reconnect_failed))
            }
        }
    }

    override fun onVideoDataReceived(data: ByteArray) {
        frameParser.onRawDataReceived(data)
    }

    override fun onStatusDataRead(brightness: Int, temperature: Int) {
        runOnUiThread {
            binding.sliderLedBrightness.value = brightness.toFloat()
            binding.tvTemperature.text = getString(R.string.format_temperature, temperature)
        }
    }

    override fun onRssiUpdated(rssi: Int) {
        runOnUiThread {
            val percent = when {
                rssi >= -50 -> 100; rssi <= -100 -> 0; else -> 100 + ((rssi + 50) * 2)
            }
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
            binding.tvSignalStrength.text = getString(R.string.format_signal_strength, percent)
        }
    }

    override fun onDeviceFound(device: BluetoothDevice) {
        runOnUiThread {
            if (currentUiState != UiState.SCANNING) return@runOnUiThread
            binding.llSkeletonContainer.clearAnimation()
            binding.llSkeletonContainer.visibility = View.GONE
            binding.rvScanDevices.visibility = View.VISIBLE
            scanDeviceList.add(device)
            deviceListAdapter.notifyItemInserted(scanDeviceList.size - 1)
        }
    }

    override fun onScanFinished(cancelled: Boolean) {
        runOnUiThread {
            if (currentUiState != UiState.SCANNING) return@runOnUiThread
            if (scanDeviceList.isEmpty()) {
                stopDeviceScan(getString(R.string.tip_scan_no_device))
            } else {
                binding.pbScanIndicator.visibility = View.GONE
                binding.tvScanTip.text = getString(R.string.tip_scan_complete)
            }
        }
    }

    override fun onFrameParsed(bitmap: Bitmap, fps: Int) {
        synchronized(this) {
            currentBitmapFrame = bitmap
        }

        if (videoRecorder.isRecording) {
            videoRecorder.feedFrame(bitmap)
        }

        runOnUiThread {
            if (currentUiState == UiState.SCANNING) return@runOnUiThread
            if (bitmap.isRecycled) return@runOnUiThread
            if (currentUiState != UiState.NORMAL) setUiState(UiState.NORMAL)
            binding.ivVideoDisplay.setImageBitmap(bitmap)
            binding.tvVideoFps.text = getString(R.string.format_video_fps, fps)
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
            ToastUtil.show(this, getString(R.string.toast_no_live_frame_screenshot))
            return
        }
        val copy = bmp.copy(Bitmap.Config.RGB_565, false) ?: return
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ESPAUTO")
        }
        try {
            val uri =
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
            contentResolver.openOutputStream(uri)
                ?.use { copy.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ToastUtil.show(this, getString(R.string.toast_screenshot_success))
        } catch (_: Exception) {
            ToastUtil.show(this, getString(R.string.toast_screenshot_failed))
        } finally {
            copy.recycle()
        }
    }

    private fun setUiState(state: UiState, tip: String = "") {
        currentUiState = state
        runOnUiThread {
            val isScanning = state == UiState.SCANNING
            binding.llScanPanel.visibility = if (isScanning) View.VISIBLE else View.GONE
            binding.flIconContainer.visibility = if (isScanning) View.GONE else View.VISIBLE
            binding.tvLoadingTip.visibility = if (isScanning) View.GONE else View.VISIBLE
            binding.rlLoadingContainer.visibility =
                if (state != UiState.NORMAL) View.VISIBLE else View.GONE
            binding.llLiveStatusLabel.visibility =
                if (state == UiState.NORMAL) View.VISIBLE else View.GONE
            binding.llDeviceInfoPanel.visibility =
                if (state == UiState.NORMAL) View.VISIBLE else View.GONE
            binding.ivNoDeviceIcon.visibility =
                if (state == UiState.IDLE) View.VISIBLE else View.GONE
            binding.ivReconnectIcon.visibility =
                if (state == UiState.RECONNECT) View.VISIBLE else View.GONE
            binding.pbLoadingIndicator.visibility =
                if (state == UiState.LOADING) View.VISIBLE else View.GONE
            if (!isScanning) binding.tvLoadingTip.text = tip

            binding.btnScanDevice.setImageResource(
                if (state == UiState.NORMAL) R.drawable.ic_bluetooth_disconnect else R.drawable.ic_bluetooth_connect
            )
        }
    }

    private fun clearVideoDisplay() {
        frameParser.clearBuffer()
        runOnUiThread { binding.ivVideoDisplay.setImageDrawable(null) }
        synchronized(this) {
            currentBitmapFrame = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupServoTouchEvents() {
        val loopRun = object : Runnable {
            override fun run() {
                if (isServoUpLongPress) {
                    bleManager.sendSimpleControlCmd(BleManager.CMD_SERVO_UP)
                    servoHandler.postDelayed(this, 80)
                } else if (isServoDownLongPress) {
                    bleManager.sendSimpleControlCmd(BleManager.CMD_SERVO_DOWN)
                    servoHandler.postDelayed(this, 80)
                }
            }
        }

        binding.btnServoUp.setOnClickListener { bleManager.sendSimpleControlCmd(BleManager.CMD_SERVO_UP) }
        binding.btnServoUp.setOnLongClickListener {
            isServoUpLongPress = true; servoHandler.post(
            loopRun
        ); true
        }
        binding.btnServoUp.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                isServoUpLongPress = false; servoHandler.removeCallbacksAndMessages(null)
            }
            false
        }

        binding.btnServoDown.setOnClickListener { bleManager.sendSimpleControlCmd(BleManager.CMD_SERVO_DOWN) }
        binding.btnServoDown.setOnLongClickListener {
            isServoDownLongPress = true; servoHandler.post(loopRun); true
        }
        binding.btnServoDown.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                isServoDownLongPress = false; servoHandler.removeCallbacksAndMessages(null)
            }
            false
        }
    }

    private fun startRecordingTimer() {
        recordStartTime = System.currentTimeMillis()
        recordTimerRunnable = object : Runnable {
            override fun run() {
                val dur = (System.currentTimeMillis() - recordStartTime) / 1000
                val timeStr = String.format(java.util.Locale.US, "%02d:%02d", dur / 60, dur % 60)
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
            binding.llLiveStatusLabel.text = text
            binding.llLiveStatusLabel.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
        }
    }

    private inner class DeviceListAdapter : RecyclerView.Adapter<DeviceViewHolder>() {

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            DeviceViewHolder(layoutInflater.inflate(R.layout.device_list_item, p, false))

        override fun getItemCount() = scanDeviceList.size

        @SuppressLint("MissingPermission")
        override fun onBindViewHolder(h: DeviceViewHolder, p: Int) {
            val dev = scanDeviceList[p]
            h.tvName.text = dev.name ?: getString(R.string.device_unknown)
            h.tvAddr.text = dev.address
            h.itemView.setOnClickListener {
                bleManager.stopScan()
                getSharedPreferences("ESPAUTO_CONFIG", MODE_PRIVATE).edit {
                    remove("saved_device_address")
                }
                targetDevice = dev
                setUiState(
                    UiState.LOADING,
                    getString(
                        R.string.tip_connecting_target_device,
                        dev.name ?: getString(R.string.device_unknown)
                    )
                )
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

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        ToastUtil.show(
                            this@MainActivity,
                            getString(R.string.toast_bluetooth_turned_off)
                        )
                        isManualDisconnect = true
                        bleManager.disconnect()
                        setUiState(UiState.IDLE, getString(R.string.tip_bluetooth_off))
                    }

                    BluetoothAdapter.STATE_ON -> executeBluetoothFlow()
                }
            }
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) autoConnectOrScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothReceiver)
        stopRecordingTimer()
        videoRecorder.stop()
        sensorManager.unregisterListener(gravitySensorListener)
        frameParser.release()
        bleManager.disconnect()

        synchronized(this) {
            currentBitmapFrame = null
        }
    }
}