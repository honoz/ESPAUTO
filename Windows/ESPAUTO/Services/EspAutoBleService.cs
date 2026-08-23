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

public class EspAutoBleService : IDisposable
{
    public static readonly Guid ServiceUuid = Guid.Parse("0000ffe0-0000-1000-8000-00805f9b34fb");
    public static readonly Guid ControlCharUuid = Guid.Parse("0000ffe2-0000-1000-8000-00805f9b34fb");
    public static readonly Guid VideoCharUuid = Guid.Parse("0000ffe1-0000-1000-8000-00805f9b34fb");
    public static readonly Guid StatusCharUuid = Guid.Parse("0000ffe3-0000-1000-8000-00805f9b34fb");

    public const byte CmdLedBrightness = 0x00;
    public const byte CmdBeep = 0x12;
    public const byte CmdServoUp = 0x13;
    public const byte CmdServoDown = 0x14;
    public const byte CmdCarMove = 0x15;

    private BluetoothLEDevice? _bleDevice;
    private GattDeviceServicesResult? _serviceResult;
    private GattCharacteristic? _controlChar;
    private GattCharacteristic? _videoChar;
    private GattCharacteristic? _statusChar;

    private readonly List<byte[]> _cmdQueue = new();
    private bool _isSendingCmd;
    private Timer? _cmdTimer;
    private Timer? _statusPollTimer;

    private readonly object _videoFrameLock = new();
    private byte[] _videoFrameBuffer = new byte[65536];
    private int _videoBufferReceivedLen;
    private int _expectFrameTotalLen;
    private int _videoOverflowLen;

    private int _frameCountFps;
    private long _lastFpsRefreshTime;

    public bool IsConnected => _bleDevice != null && _controlChar != null;
    public bool IsScanning { get; private set; }

    public event Action<ConnectionStatus>? ConnectionStatusChanged;
    public event Action<byte[]>? VideoFrameReceived;
    public event Action<DeviceStatusData>? StatusUpdated;
    public event Action<int>? FpsUpdated;
    public event Action<List<BleDeviceInfo>>? DevicesDiscovered;
    public event Action<bool>? ScanStateChanged;

    public EspAutoBleService()
    {
        _cmdTimer = new Timer(_ => ProcessNextCmdAsync(), null,
            TimeSpan.FromMilliseconds(15), TimeSpan.FromMilliseconds(15));
        _lastFpsRefreshTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    }

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
                        catch {}
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

    public async Task ConnectAsync()
    {
        try
        {
            CleanupBle();

            ConnectionStatusChanged?.Invoke(ConnectionStatus.Searching);

            BluetoothLEDevice? targetDevice = null;
            var watcher = new Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher();

            var manufacturerData = new Windows.Devices.Bluetooth.Advertisement.BluetoothLEManufacturerData();
            manufacturerData.CompanyId = 0xE5E5;
            using (var writer = new DataWriter())
            {
                manufacturerData.Data = writer.DetachBuffer();
            }
            watcher.AdvertisementFilter.Advertisement.ManufacturerData.Add(manufacturerData);

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
                    catch {}
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
                        catch {}
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

    public void SendCarMoveCmd(sbyte speed, sbyte steer)
    {
        var cmd = new byte[] { 0xAA, 0xFF, CmdCarMove, (byte)speed, (byte)steer };
        AddCmdToQueue(cmd);
    }

    public void SendLedBrightnessCmd(int brightnessPercent)
    {
        byte rawBright = (byte)Math.Round((brightnessPercent / 100.0) * 255);
        AddCmdToQueue(new byte[] { 0xAA, 0xFF, CmdLedBrightness, rawBright });
    }

    public void SendBeepCmd() => AddCmdToQueue(new byte[] { 0xAA, 0xFF, CmdBeep });

    public void SendServoUpCmd() => AddCmdToQueue(new byte[] { 0xAA, 0xFF, CmdServoUp });

    public void SendServoDownCmd() => AddCmdToQueue(new byte[] { 0xAA, 0xFF, CmdServoDown });

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
            catch {}
        }
    }

    private void AddCmdToQueue(byte[] cmdBytes)
    {
        if (_controlChar == null) return;

        lock (_cmdQueue)
        {
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
        catch {}
        finally
        {
            _isSendingCmd = false;
        }
    }

    private void OnVideoDataReceived(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var data = ToByteArray(args.CharacteristicValue);
        if (data.Length < 2) return;

        lock (_videoFrameLock)
        {
            if (data.Length >= 4 && data[0] == 0xAB && data[1] == 0xCD)
            {

                _expectFrameTotalLen = (data[2] << 8) | data[3];
                _videoBufferReceivedLen = 0;
                _videoOverflowLen = 0;

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

            if (_expectFrameTotalLen <= 0 || _videoBufferReceivedLen >= _expectFrameTotalLen) return;

            int spaceLeft = _expectFrameTotalLen - _videoBufferReceivedLen;
            int toCopy = Math.Min(data.Length, spaceLeft);
            if (toCopy > 0 && _videoBufferReceivedLen + toCopy <= _videoFrameBuffer.Length)
            {
                System.Buffer.BlockCopy(data, 0, _videoFrameBuffer, _videoBufferReceivedLen, toCopy);
                _videoBufferReceivedLen += toCopy;
            }

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

        if (_videoFrameBuffer[0] != 0xFF || _videoFrameBuffer[1] != 0xD8)
        {
            ResetFrameState();
            return;
        }

        var fullFrame = new byte[_expectFrameTotalLen];
        System.Buffer.BlockCopy(_videoFrameBuffer, 0, fullFrame, 0, _expectFrameTotalLen);

        _frameCountFps++;
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (now - _lastFpsRefreshTime >= 1000)
        {
            FpsUpdated?.Invoke(_frameCountFps);
            _frameCountFps = 0;
            _lastFpsRefreshTime = now;
        }

        VideoFrameReceived?.Invoke(fullFrame);

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
