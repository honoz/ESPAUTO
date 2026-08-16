/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;
using ESPAUTO.Models;

namespace ESPAUTO.Services;

/// <summary>
/// ESPAUTO BLE 通信服务
/// 负责蓝牙设备发现、连接、命令发送、视频流接收和状态轮询
/// </summary>
public class EspAutoBleService : IDisposable
{
    // ─── BLE UUID ───────────────────────────────────────────
    public static readonly Guid ServiceUuid = Guid.Parse("0000ffe0-0000-1000-8000-00805f9b34fb");
    public static readonly Guid ControlCharUuid = Guid.Parse("0000ffe2-0000-1000-8000-00805f9b34fb");
    public static readonly Guid VideoCharUuid = Guid.Parse("0000ffe1-0000-1000-8000-00805f9b34fb");
    public static readonly Guid StatusCharUuid = Guid.Parse("0000ffe3-0000-1000-8000-00805f9b34fb");

    // ─── 命令码 ─────────────────────────────────────────────
    public const byte CmdLedBrightness = 0x00;
    public const byte CmdBeep = 0x12;
    public const byte CmdServoUp = 0x13;
    public const byte CmdServoDown = 0x14;
    public const byte CmdCarMove = 0x15;

    // ─── 内部状态 ───────────────────────────────────────────
    private BluetoothLEDevice? _bleDevice;
    private GattDeviceServicesResult? _serviceResult;
    private GattCharacteristic? _controlChar;
    private GattCharacteristic? _videoChar;
    private GattCharacteristic? _statusChar;

    private readonly List<byte[]> _cmdQueue = new();
    private bool _isSendingCmd;
    private Timer? _cmdTimer;
    private Timer? _statusPollTimer;

    // 视频帧组装（直接缓冲区，避免 List<byte[]> 碎片分配）
    private readonly object _videoFrameLock = new();
    private byte[] _videoFrameBuffer = new byte[65536];
    private int _videoBufferReceivedLen;
    private int _expectFrameTotalLen;
    private int _videoOverflowLen;

    // FPS 计算
    private int _frameCountFps;
    private long _lastFpsRefreshTime;

    // ─── 公共属性 ───────────────────────────────────────────
    public bool IsConnected => _bleDevice != null && _controlChar != null;
    public bool IsScanning { get; private set; }

    // ─── 事件 ──────────────────────────────────────────────
    public event Action<ConnectionStatus>? ConnectionStatusChanged;
    public event Action<byte[]>? VideoFrameReceived;
    public event Action<DeviceStatusData>? StatusUpdated;
    public event Action<int>? FpsUpdated;
    public event Action<List<BleDeviceInfo>>? DevicesDiscovered;
    public event Action<bool>? ScanStateChanged;

    /// <summary>
    /// 构造函数，启动命令队列定时器
    /// </summary>
    public EspAutoBleService()
    {
        _cmdTimer = new Timer(_ => ProcessNextCmdAsync(), null,
            TimeSpan.FromMilliseconds(15), TimeSpan.FromMilliseconds(15));
        _lastFpsRefreshTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    }

    // ─── 设备扫描 ───────────────────────────────────────────

    private Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher? _scanWatcher;
    private readonly Dictionary<ulong, BleDeviceInfo> _discoveredDevices = new();
    private readonly object _scanLock = new();

    public void StartScan()
    {
        if (IsScanning) return;
        try
        {
            IsScanning = true;
            lock (_scanLock) _discoveredDevices.Clear();
            ScanStateChanged?.Invoke(true);

            _scanWatcher = new Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher();
            _scanWatcher.SignalStrengthFilter.OutOfRangeThresholdInDBm = -100;
            var manufacturerData = new Windows.Devices.Bluetooth.Advertisement.BluetoothLEManufacturerData();
            manufacturerData.CompanyId = 0xE5E5;
            using (var writer = new DataWriter())
                manufacturerData.Data = writer.DetachBuffer();
            _scanWatcher.AdvertisementFilter.Advertisement.ManufacturerData.Add(manufacturerData);

            _scanWatcher.Received += (s, args) =>
            {
                try
                {
                    var addr = args.BluetoothAddress;
                    bool isNew = false;
                    lock (_scanLock)
                    {
                        if (!_discoveredDevices.ContainsKey(addr))
                        {
                            var localName = args.Advertisement.LocalName;
                            _discoveredDevices[addr] = new BleDeviceInfo
                            {
                                Address = addr,
                                Name = string.IsNullOrEmpty(localName) ? $"ESPAUTO ({addr:X12})" : localName,
                                Rssi = args.RawSignalStrengthInDBm
                            };
                            isNew = true;
                        }
                        else
                        {
                            _discoveredDevices[addr].Rssi = args.RawSignalStrengthInDBm;
                        }
                    }
                    if (isNew)
                    {
                        List<BleDeviceInfo> snapshot;
                        lock (_scanLock) snapshot = new List<BleDeviceInfo>(_discoveredDevices.Values);
                        DevicesDiscovered?.Invoke(snapshot);
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"Scan Received callback error: {ex.Message}");
                }
            };

            _scanWatcher.Stopped += (s, args) =>
            {
                IsScanning = false;
                ScanStateChanged?.Invoke(false);
            };

            _scanWatcher.Start();
        }
        catch (Exception ex)
        {
            IsScanning = false;
            ScanStateChanged?.Invoke(false);
            System.Diagnostics.Debug.WriteLine($"StartScan failed: {ex.Message}");
        }
    }

    public void StopScan()
    {
        if (!IsScanning) return;
        _scanWatcher?.Stop();
    }

    public async Task ConnectToDeviceAsync(ulong address)
    {
        StopScan();
        try
        {
            CleanupBle();
            ConnectionStatusChanged?.Invoke(ConnectionStatus.Linking);

            var targetDevice = await BluetoothLEDevice.FromBluetoothAddressAsync(address);
            if (targetDevice == null)
            {
                ConnectionStatusChanged?.Invoke(ConnectionStatus.Error);
                return;
            }

            _bleDevice = targetDevice;
            _bleDevice.ConnectionStatusChanged += OnBleConnectionStatusChanged;

            var gattResult = await _bleDevice.GetGattServicesAsync(BluetoothCacheMode.Uncached);
            if (gattResult.Status != GattCommunicationStatus.Success)
                throw new Exception("GATT services discovery failed");

            _serviceResult = gattResult;
            var service = gattResult.Services.FirstOrDefault(s => s.Uuid == ServiceUuid);
            if (service == null)
                throw new Exception("ESPAUTO BLE service not found");

            var controlResult = await service.GetCharacteristicsAsync(BluetoothCacheMode.Uncached);
            if (controlResult.Status == GattCommunicationStatus.Success)
            {
                _controlChar = controlResult.Characteristics.FirstOrDefault(c => c.Uuid == ControlCharUuid);
                _videoChar = controlResult.Characteristics.FirstOrDefault(c => c.Uuid == VideoCharUuid);
                _statusChar = controlResult.Characteristics.FirstOrDefault(c => c.Uuid == StatusCharUuid);
            }

            if (_videoChar != null)
            {
                _videoChar.ValueChanged += OnVideoDataReceived;
                await _videoChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.Notify);
            }

            if (_statusChar != null)
            {
                var initValue = await _statusChar.ReadValueAsync();
                if (initValue.Status == GattCommunicationStatus.Success)
                    HandleStatusData(ToByteArray(initValue.Value), true);

                _statusPollTimer = new Timer(async _ =>
                {
                    if (_bleDevice?.ConnectionStatus == BluetoothConnectionStatus.Connected && _statusChar != null)
                    {
                        try
                        {
                            var val = await _statusChar.ReadValueAsync();
                            if (val.Status == GattCommunicationStatus.Success)
                                HandleStatusData(ToByteArray(val.Value), false);
                        }
                        catch { }
                    }
                }, null, TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(2));
            }

            if (_controlChar != null)
                ConnectionStatusChanged?.Invoke(ConnectionStatus.Online);
            else
                throw new Exception("控制特征未找到");
        }
        catch
        {
            ConnectionStatusChanged?.Invoke(ConnectionStatus.Error);
        }
    }

    // ─── 连接 / 断开 ────────────────────────────────────────

    /// <summary>
    /// 搜索并连接 BLE 设备 (厂商数据 0xE5E5) - 保留用于兼容
    /// </summary>
    public async Task ConnectAsync()
    {
        try
        {
            // 清理旧连接
            CleanupBle();

            ConnectionStatusChanged?.Invoke(ConnectionStatus.Searching);

            // 使用 BLE Advertisement Watcher 扫描含厂商数据 0xE5E5 的设备
            BluetoothLEDevice? targetDevice = null;
            var watcher = new Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher();

            // 设置厂商数据过滤器
            var manufacturerData = new Windows.Devices.Bluetooth.Advertisement.BluetoothLEManufacturerData();
            manufacturerData.CompanyId = 0xE5E5;
            // 空数据段，仅匹配 CompanyId
            using (var writer = new DataWriter())
            {
                manufacturerData.Data = writer.DetachBuffer();
            }
            watcher.AdvertisementFilter.Advertisement.ManufacturerData.Add(manufacturerData);

            // 扫描 8 秒
            var tcs = new TaskCompletionSource<bool>();
            ulong foundAddress = 0;
            watcher.Received += (s, args) =>
            {
                foundAddress = args.BluetoothAddress;
                if (!tcs.Task.IsCompleted) tcs.TrySetResult(true);
            };

            watcher.Start();
            var completed = await Task.WhenAny(tcs.Task, Task.Delay(8000));
            watcher.Stop();

            if (foundAddress != 0)
            {
                targetDevice = await BluetoothLEDevice.FromBluetoothAddressAsync(foundAddress);
            }

            if (targetDevice == null)
            {
                // 回退：枚举已配对的 BLE 设备
                var pairedDevices = await Windows.Devices.Enumeration.DeviceInformation.FindAllAsync(
                    BluetoothLEDevice.GetDeviceSelectorFromPairingState(true));

                foreach (var devInfo in pairedDevices)
                {
                    try
                    {
                        var bleDev = await BluetoothLEDevice.FromIdAsync(devInfo.Id);
                        if (bleDev != null)
                        {
                            targetDevice = bleDev;
                            break;
                        }
                    }
                    catch { /* 跳过无法打开的设备 */ }
                }
            }

            if (targetDevice == null)
            {
                ConnectionStatusChanged?.Invoke(ConnectionStatus.Error);
                return;
            }

            _bleDevice = targetDevice;

            if (_bleDevice == null)
            {
                ConnectionStatusChanged?.Invoke(ConnectionStatus.Error);
                return;
            }

            _bleDevice.ConnectionStatusChanged += OnBleConnectionStatusChanged;
            ConnectionStatusChanged?.Invoke(ConnectionStatus.Linking);

            // 连接 GATT 服务
            var gattResult = await _bleDevice.GetGattServicesAsync(BluetoothCacheMode.Uncached);
            if (gattResult.Status != GattCommunicationStatus.Success)
                throw new Exception("GATT services discovery failed");

            _serviceResult = gattResult;
            var service = gattResult.Services.FirstOrDefault(s => s.Uuid == ServiceUuid);
            if (service == null)
                throw new Exception("ESPAUTO BLE service not found");

            // 获取控制特征
            var controlResult = await service.GetCharacteristicsAsync(BluetoothCacheMode.Uncached);
            if (controlResult.Status == GattCommunicationStatus.Success)
            {
                _controlChar = controlResult.Characteristics.FirstOrDefault(c => c.Uuid == ControlCharUuid);
                _videoChar = controlResult.Characteristics.FirstOrDefault(c => c.Uuid == VideoCharUuid);
                _statusChar = controlResult.Characteristics.FirstOrDefault(c => c.Uuid == StatusCharUuid);
            }

            // 订阅视频通知
            if (_videoChar != null)
            {
                _videoChar.ValueChanged += OnVideoDataReceived;
                await _videoChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.Notify);
            }

            // 读取初始状态并启动轮询
            if (_statusChar != null)
            {
                var initValue = await _statusChar.ReadValueAsync();
                if (initValue.Status == GattCommunicationStatus.Success)
                {
                    HandleStatusData(ToByteArray(initValue.Value), true);
                }

                _statusPollTimer = new Timer(async _ =>
                {
                    if (_bleDevice?.ConnectionStatus == BluetoothConnectionStatus.Connected && _statusChar != null)
                    {
                        try
                        {
                            var val = await _statusChar.ReadValueAsync();
                            if (val.Status == GattCommunicationStatus.Success)
                                HandleStatusData(ToByteArray(val.Value), false);
                        }
                        catch { /* 轮询失败忽略 */ }
                    }
                }, null, TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(2));
            }

            if (_controlChar != null)
            {
                ConnectionStatusChanged?.Invoke(ConnectionStatus.Online);
            }
            else
            {
                throw new Exception("控制特征未找到");
            }
        }
        catch
        {
            ConnectionStatusChanged?.Invoke(ConnectionStatus.Error);
        }
    }

    /// <summary>
    /// 断开 BLE 连接
    /// </summary>
    public void Disconnect()
    {
        if (_bleDevice != null && IsConnected)
        {
            ForceStopCar();
        }

        CleanupBle();
        ConnectionStatusChanged?.Invoke(ConnectionStatus.Offline);
    }

    private void OnBleConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        if (sender.ConnectionStatus != BluetoothConnectionStatus.Connected)
        {
            CleanupBle();
            ConnectionStatusChanged?.Invoke(ConnectionStatus.Offline);
        }
    }

    private void CleanupBle()
    {
        _statusPollTimer?.Dispose();
        _statusPollTimer = null;
        // 注意：_cmdTimer 是持久化定时器，不在这里销毁

        if (_videoChar != null)
        {
            _videoChar.ValueChanged -= OnVideoDataReceived;
            _videoChar = null;
        }

        _controlChar = null;
        _statusChar = null;

        if (_serviceResult != null)
        {
            foreach (var svc in _serviceResult.Services)
                svc?.Dispose();
            _serviceResult = null;
        }

        _bleDevice?.Dispose();
        _bleDevice = null;

        lock (_cmdQueue) _cmdQueue.Clear();
        _videoBufferReceivedLen = 0;
        _expectFrameTotalLen = 0;
        _videoOverflowLen = 0;
    }

    // ─── 命令发送 ───────────────────────────────────────────

    /// <summary>发送车辆移动命令</summary>
    public void SendCarMoveCmd(sbyte speed, sbyte steer)
    {
        var cmd = new byte[] { 0xAA, 0xFF, CmdCarMove, (byte)speed, (byte)steer };
        AddCmdToQueue(cmd);
    }

    /// <summary>发送 LED 亮度命令</summary>
    public void SendLedBrightnessCmd(int brightnessPercent)
    {
        byte rawBright = (byte)Math.Round((brightnessPercent / 100.0) * 255);
        AddCmdToQueue(new byte[] { 0xAA, 0xFF, CmdLedBrightness, rawBright });
    }

    /// <summary>发送鸣笛命令</summary>
    public void SendBeepCmd() => AddCmdToQueue(new byte[] { 0xAA, 0xFF, CmdBeep });

    /// <summary>发送云台抬起命令</summary>
    public void SendServoUpCmd() => AddCmdToQueue(new byte[] { 0xAA, 0xFF, CmdServoUp });

    /// <summary>发送云台降下命令</summary>
    public void SendServoDownCmd() => AddCmdToQueue(new byte[] { 0xAA, 0xFF, CmdServoDown });

    /// <summary>强制停车</summary>
    public async void ForceStopCar()
    {
        var cmd = new byte[] { 0xAA, 0xFF, CmdCarMove, 0, 0 };
        if (_controlChar != null)
        {
            try
            {
                using var writer = new DataWriter();
                writer.WriteBytes(cmd);
                await _controlChar.WriteValueAsync(writer.DetachBuffer());
            }
            catch { /* 忽略断连时的写入错误 */ }
        }
    }

    private void AddCmdToQueue(byte[] cmdBytes)
    {
        if (_controlChar == null) return;

        lock (_cmdQueue)
        {
            // 移动命令只保留最新的一条
            if (_cmdQueue.Count > 2 && cmdBytes.Length >= 3 && cmdBytes[2] == CmdCarMove)
            {
                _cmdQueue.RemoveAll(c => c.Length >= 3 && c[2] == CmdCarMove);
            }
            _cmdQueue.Add(cmdBytes);
        }
    }

    private async void ProcessNextCmdAsync()
    {
        if (_isSendingCmd || _controlChar == null) return;

        byte[]? cmd;
        lock (_cmdQueue)
        {
            if (_cmdQueue.Count == 0) return;
            cmd = _cmdQueue[0];
            _cmdQueue.RemoveAt(0);
        }

        _isSendingCmd = true;
        try
        {
            using var writer = new DataWriter();
            writer.WriteBytes(cmd);
            await _controlChar.WriteValueAsync(writer.DetachBuffer());
        }
        catch { /* 写入失败忽略 */ }
        finally
        {
            _isSendingCmd = false;
        }
    }

    // ─── 视频数据处理 ───────────────────────────────────────

    private void OnVideoDataReceived(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var data = ToByteArray(args.CharacteristicValue);
        if (data.Length < 2) return;

        lock (_videoFrameLock)
        {
            // 帧头: 0xAB 0xCD + 2字节JPEG总长度
            if (data.Length >= 4 && data[0] == 0xAB && data[1] == 0xCD)
            {
                // 如果上一帧未组装完就收到新帧头，丢弃不完整的旧帧
                // （这是导致花屏的主要原因：帧被截断后仍被显示）

                _expectFrameTotalLen = (data[2] << 8) | data[3];
                _videoBufferReceivedLen = 0;
                _videoOverflowLen = 0;

                // 处理帧头包中可能携带的溢出数据（BLE 合并通知时）
                if (data.Length > 4)
                {
                    int overflowLen = data.Length - 4;
                    int copyLen = Math.Min(overflowLen, _expectFrameTotalLen);
                    if (copyLen > 0 && _expectFrameTotalLen <= _videoFrameBuffer.Length)
                        System.Buffer.BlockCopy(data, 4, _videoFrameBuffer, 0, copyLen);
                    _videoBufferReceivedLen = copyLen;

                    if (_videoBufferReceivedLen >= _expectFrameTotalLen)
                    {
                        _videoOverflowLen = overflowLen - copyLen;
                        EmitVideoFrame();
                        // 处理溢出到下一帧的数据
                        if (_videoOverflowLen > 0 && data.Length >= 4 + copyLen + 4)
                        {
                            int ovStart = 4 + copyLen;
                            if (data[ovStart] == 0xAB && data[ovStart + 1] == 0xCD && ovStart + 4 <= data.Length)
                            {
                                _expectFrameTotalLen = (data[ovStart + 2] << 8) | data[ovStart + 3];
                                _videoBufferReceivedLen = 0;
                                _videoOverflowLen = 0;
                                int remaining = data.Length - ovStart - 4;
                                if (remaining > 0 && _expectFrameTotalLen > 0 && _expectFrameTotalLen <= _videoFrameBuffer.Length)
                                {
                                    int copy2 = Math.Min(remaining, _expectFrameTotalLen);
                                    System.Buffer.BlockCopy(data, ovStart + 4, _videoFrameBuffer, 0, copy2);
                                    _videoBufferReceivedLen = copy2;
                                    if (_videoBufferReceivedLen >= _expectFrameTotalLen)
                                        EmitVideoFrame();
                                }
                            }
                        }
                    }
                }
                return;
            }

            // 非帧头数据：追加到帧缓冲区
            if (_expectFrameTotalLen <= 0 || _videoBufferReceivedLen >= _expectFrameTotalLen) return;

            int spaceLeft = _expectFrameTotalLen - _videoBufferReceivedLen;
            int toCopy = Math.Min(data.Length, spaceLeft);
            if (toCopy > 0 && _videoBufferReceivedLen + toCopy <= _videoFrameBuffer.Length)
            {
                System.Buffer.BlockCopy(data, 0, _videoFrameBuffer, _videoBufferReceivedLen, toCopy);
                _videoBufferReceivedLen += toCopy;
            }

            // 记录超出声明长度的溢出数据
            if (_videoBufferReceivedLen >= _expectFrameTotalLen)
            {
                _videoOverflowLen = data.Length - toCopy;
                EmitVideoFrame();
            }
        }
    }

    private void EmitVideoFrame()
    {
        if (_expectFrameTotalLen < 2) { ResetFrameState(); return; }

        // 验证 JPEG SOI 标记 (0xFF 0xD8)
        if (_videoFrameBuffer[0] != 0xFF || _videoFrameBuffer[1] != 0xD8)
        {
            ResetFrameState();
            return;
        }

        // 拷贝完整帧数据用于发送（必须在重置状态前拷贝）
        var fullFrame = new byte[_expectFrameTotalLen];
        System.Buffer.BlockCopy(_videoFrameBuffer, 0, fullFrame, 0, _expectFrameTotalLen);

        // FPS 计算
        _frameCountFps++;
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (now - _lastFpsRefreshTime >= 1000)
        {
            FpsUpdated?.Invoke(_frameCountFps);
            _frameCountFps = 0;
            _lastFpsRefreshTime = now;
        }

        // 通知 UI 层
        VideoFrameReceived?.Invoke(fullFrame);

        // 保存溢出长度，重置帧状态
        int overflow = _videoOverflowLen;
        _expectFrameTotalLen = 0;
        _videoBufferReceivedLen = 0;
        _videoOverflowLen = 0;
    }

    private void ResetFrameState()
    {
        _expectFrameTotalLen = 0;
        _videoBufferReceivedLen = 0;
        _videoOverflowLen = 0;
    }

    // ─── 状态数据处理 ───────────────────────────────────────

    private void HandleStatusData(byte[] data, bool isInitial)
    {
        if (data == null || data.Length < 1) return;

        var status = new DeviceStatusData { IsInitial = isInitial };

        byte rawBright = data[0];
        status.BrightnessPercent = (int)Math.Round((rawBright / 255.0) * 100);

        if (data.Length >= 2)
        {
            byte temp = data[1];
            status.Temperature = (temp == 0xFF) ? -1 : temp;
        }

        StatusUpdated?.Invoke(status);
    }

    // ─── 工具方法 ───────────────────────────────────────────

    private static byte[] ToByteArray(IBuffer buffer)
    {
        using var reader = DataReader.FromBuffer(buffer);
        var data = new byte[buffer.Length];
        reader.ReadBytes(data);
        return data;
    }

    public void Dispose()
    {
        CleanupBle();
        _cmdTimer?.Dispose();
        _cmdTimer = null;
        _statusPollTimer?.Dispose();
    }
}
