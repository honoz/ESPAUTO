/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/
using System;
using System.Collections.Generic;
using System.Linq;
using System.Collections.ObjectModel;
using Microsoft.UI.Dispatching;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;
using Windows.Storage;
using Windows.Storage.Streams;
using ESPAUTO.Models;
using ESPAUTO.Services;

namespace ESPAUTO;

public sealed partial class MainWindow : Window
{
    private readonly EspAutoBleService _bleService;
    private readonly DispatcherQueue _dispatcherQueue;
    private readonly Dictionary<string, bool> _keyStates = new() { ["w"]=false, ["s"]=false, ["a"]=false, ["d"]=false };
    private DispatcherTimer? _driveLoopTimer, _servoTimer;
    private sbyte _lastSentSpeed, _lastSentSteer;
    private byte[]? _latestFrameData;
    private SoftwareBitmap? _latestSoftwareBitmap;
    private bool _isDecodingFrame;
    private bool _isRecording;
    private DispatcherTimer? _recordTimer;
    private DateTime _recordStartTime;
    private readonly List<byte[]> _recordedFrames = new();
    private string? _recordTempDir;
    private int _recordFrameCount;
    private string _currentLang;
    private bool _isZh;
    private ulong _selectedDeviceAddress;
    private readonly ObservableCollection<BleDeviceInfo> _devices = new();
    private readonly Dictionary<string, Dictionary<string, string>> _i18n = new()
    {
        ["zh-CN"] = new() { ["statusStandby"]="设备就绪", ["statusSearching"]="正在搜索", ["statusLinking"]="正在配对", ["statusActive"]="系统就绪", ["statusError"]="连接异常", ["statusOnline"]="已连接", ["statusOffline"]="未连接", ["videoTip"]="等待无线图传建立...", ["btnConnect"]="建立连接", ["btnDisconnect"]="断开", ["dashTitle"]="系统状态与控制", ["lblLink"]="连接状态", ["lblFps"]="实时帧率", ["lblTemp"]="终端温度", ["lblBright"]="照明功率", ["sliderLed"]="灯光控制", ["btnBeep"]="鸣笛", ["btnSnap"]="快照", ["btnRecord"]="录制", ["btnRecordStop"]="停止", ["guideTitle"]="操控集群说明:", ["guideMove"]="车身移动：W (前) / S (后) / A (左旋) / D (右旋)", ["guideServo"]="精密云台：I (抬起) / K (降下)", ["guideBeep"]="应答模块：Space (短促鸣笛)", ["guideTip"]="提示：遥控前请确保窗口处于激活状态。", ["scanTitle"]="设备扫描", ["scanStatus"]="点击扫描按钮搜索附近的低功耗蓝牙设备", ["scanBtn"]="开始扫描", ["scanStop"]="停止扫描", ["deviceList"]="发现的设备", ["noDevice"]="暂无设备", ["connectSelected"]="连接选中设备", ["back"]="返回" },
        ["en"] = new() { ["statusStandby"]="DEVICE STANDBY", ["statusSearching"]="SEARCHING", ["statusLinking"]="PAIRING", ["statusActive"]="SYSTEM ACTIVE", ["statusError"]="CONNECTION ERROR", ["statusOnline"]="ONLINE", ["statusOffline"]="OFFLINE", ["videoTip"]="Establishing wireless video link...", ["btnConnect"]="Connect", ["btnDisconnect"]="Disconnect", ["dashTitle"]="System Status & Control", ["lblLink"]="Connection", ["lblFps"]="Real-time FPS", ["lblTemp"]="Device Temp", ["lblBright"]="LED Power", ["sliderLed"]="Light Control", ["btnBeep"]="Horn", ["btnSnap"]="Snapshot", ["btnRecord"]="Record", ["btnRecordStop"]="Stop", ["guideTitle"]="Cluster Control Guide:", ["guideMove"]="Chassis: W (Fwd) / S (Bwd) / A (Left) / D (Right)", ["guideServo"]="Gimbal: I (Tilt Up) / K (Tilt Down)", ["guideBeep"]="Response Module: Space (Short Honk)", ["guideTip"]="Tip: Ensure the window is focused before controlling.", ["scanTitle"]="Device Scan", ["scanStatus"]="Click scan to search for nearby BLE devices", ["scanBtn"]="Start Scan", ["scanStop"]="Stop Scan", ["deviceList"]="Discovered Devices", ["noDevice"]="No devices found", ["connectSelected"]="Connect Selected Device", ["back"]="Back" }
    };

    private static readonly string _logPath = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "ESPAUTO_crash.log");
    private static void Log(string msg) {
        try { System.IO.File.AppendAllText(_logPath, $"[{DateTime.Now:HH:mm:ss.fff}] {msg}\n"); } catch { }
    }

    public MainWindow()
    {
        this.InitializeComponent();
        Log("Constructor: after InitializeComponent");

        try {
            var pkgPath = Windows.ApplicationModel.Package.Current.InstalledLocation.Path;
            var iconPath = System.IO.Path.Combine(pkgPath, "Assets", "app.ico");
            Log($"Icon path: {iconPath}, exists: {System.IO.File.Exists(iconPath)}");
            this.AppWindow.SetIcon(iconPath);
        } catch (Exception ex) {
            Log($"SetIcon failed: {ex.Message}");
        }

        this.AppWindow.Resize(new Windows.Graphics.SizeInt32(1000, 900));

        this.SystemBackdrop = new MicaBackdrop();
        Log("Constructor: DesktopAcrylicBackdrop set");

        var titleBar = this.AppWindow.TitleBar;
        titleBar.ExtendsContentIntoTitleBar = true;
        titleBar.ButtonBackgroundColor = Colors.Transparent;
        titleBar.ButtonInactiveBackgroundColor = Colors.Transparent;
        Log("Constructor: TitleBar configured");

        _dispatcherQueue = DispatcherQueue.GetForCurrentThread();
        _bleService = new EspAutoBleService();
        Log("Constructor: BleService created");

        _bleService.ConnectionStatusChanged += OnConnStatus;
        _bleService.VideoFrameReceived += OnVideoFrame;
        _bleService.StatusUpdated += OnStatus;
        _bleService.FpsUpdated += fps => _dispatcherQueue.TryEnqueue(() => TxtFps.Text = $"{fps} FPS");
        _bleService.DevicesDiscovered += devices => _dispatcherQueue.TryEnqueue(() => UpdateDeviceList(devices));
        _bleService.ScanStateChanged += scanning => _dispatcherQueue.TryEnqueue(() => UpdateScanUI(scanning));
        Log("Constructor: events subscribed");

        DeviceList.ItemsSource = _devices;
        DeviceList.SelectionChanged += (s, e) => {
            if (DeviceList.SelectedItem is BleDeviceInfo dev) {
                _selectedDeviceAddress = dev.Address;
                BtnConnectDevice.IsEnabled = true;
            } else {
                BtnConnectDevice.IsEnabled = false;
            }
        };

        VideoBorder.SizeChanged += (s, e) => {
            var w = VideoBorder.ActualWidth;
            if (w > 0) VideoBorder.Height = w * 3.0 / 4.0;
        };
        VideoBorder.Height = 400;
        Log("Constructor: DeviceList setup");

        this.Activated += (s, e) => {
            if (e.WindowActivationState != WindowActivationState.Deactivated) RootGrid.Focus(FocusState.Programmatic);
            else { ResetKeys(); _bleService.ForceStopCar(); }
        };
        var drv = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(40) };
        drv.Tick += (s, e) => DriveTick(); drv.Start(); _driveLoopTimer = drv;
        Log("Constructor: timers started");

        var sysLang = Windows.System.UserProfile.GlobalizationPreferences.Languages.FirstOrDefault() ?? "";
        _isZh = sysLang.StartsWith("zh", StringComparison.OrdinalIgnoreCase);
        _currentLang = _isZh ? "zh-CN" : "en";
        Log($"Constructor: system lang={sysLang}, using {_currentLang}");

        ApplyLang();
        Log("Constructor: ApplyLang done - COMPLETE");
    }

    private void UpdateDeviceList(List<BleDeviceInfo> devices) {
        _devices.Clear();
        foreach (var d in devices) _devices.Add(d);
        NoDeviceText.Visibility = devices.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void UpdateScanUI(bool scanning) {
        if (scanning) {
            BtnScanText.Text = _i18n[_currentLang]["scanStop"];
            ScanStatusText.Text = "正在扫描...";
        } else {
            BtnScanText.Text = _i18n[_currentLang]["scanBtn"];
            ScanStatusText.Text = _devices.Count > 0
                ? $"扫描完成，发现 {_devices.Count} 个设备"
                : _i18n[_currentLang]["scanStatus"];
        }
    }

    private void BtnScan_Click(object s, RoutedEventArgs e) {
        Log($"BtnScan_Click entered, IsScanning={_bleService.IsScanning}");
        try {
            _ = DispatcherQueue.TryEnqueue(() => {
                try {
                    if (_bleService.IsScanning) _bleService.StopScan();
                    else _bleService.StartScan();
                    Log("Scan operation completed OK");
                } catch (Exception ex) {
                    Log($"Scan operation EXCEPTION: {ex}");
                }
            });
        } catch (Exception ex) {
            Log($"BtnScan_Click EXCEPTION: {ex}");
        }
    }

    private async void BtnConnectDevice_Click(object s, RoutedEventArgs e) {
        if (_bleService.IsConnected) {
            _bleService.Disconnect();
        } else {
            if (_selectedDeviceAddress == 0) return;
            BtnConnectDevice.IsEnabled = false;
            await _bleService.ConnectToDeviceAsync(_selectedDeviceAddress);
        }
    }

    private void ApplyLang() {
        var d = _i18n[_currentLang];
        VideoTipText.Text = d["videoTip"];
        LblLink.Text = d["lblLink"]; LblFps.Text = d["lblFps"];
        LblTemp.Text = d["lblTemp"]; LblBright.Text = d["lblBright"]; SliderLedLabel.Text = d["sliderLed"];
        BtnBeepText.Text = d["btnBeep"]; BtnSnapText.Text = d["btnSnap"]; LblRecord.Text = d["btnRecord"];
        GuideTitleText.Text = d["guideTitle"]; GuideMoveText.Text = d["guideMove"];
        GuideServoText.Text = d["guideServo"]; GuideBeepText.Text = d["guideBeep"]; GuideTipText.Text = d["guideTip"];
        ScanStatusText.Text = d["scanStatus"];
        BtnScanText.Text = d["scanBtn"];
        UpdateStateUI();
    }

    private void UpdateStateUI() {
        var d = _i18n[_currentLang];
        if (!_bleService.IsConnected) {
            var st = TxtState.Text;
            if (st != d["statusSearching"] && st != d["statusLinking"] && st != d["statusError"]) {
                TxtState.Text = d["statusOffline"];
            }
        } else {
            TxtState.Text = d["statusOnline"];
        }
    }

    private void OnConnStatus(ConnectionStatus st) {
        _dispatcherQueue.TryEnqueue(() => {
            var d = _i18n[_currentLang]; bool ok = st == ConnectionStatus.Online;
            switch (st) {
                case ConnectionStatus.Searching: TxtState.Text = d["statusSearching"]; break;
                case ConnectionStatus.Linking: TxtState.Text = d["statusLinking"]; break;
                case ConnectionStatus.Online:
                    TxtState.Text = d["statusOnline"];
                    BtnConnectText.Text = d["btnDisconnect"];
                    BtnConnectIcon.Glyph = "\uE711";
                    BtnConnectDevice.IsEnabled = true;
                    BtnConnectDevice.Background = new SolidColorBrush(Windows.UI.Color.FromArgb(255,220,53,69));
                    break;
                case ConnectionStatus.Error: TxtState.Text = d["statusError"]; break;
                case ConnectionStatus.Offline:
                    TxtState.Text = d["statusOffline"];
                    BtnConnectText.Text = d["connectSelected"];
                    BtnConnectIcon.Glyph = "\uE768";
                    BtnConnectDevice.IsEnabled = _selectedDeviceAddress != 0;
                    BtnConnectDevice.ClearValue(Control.BackgroundProperty);
                    break;
            }
            SliderLed.IsEnabled = ok;
            BtnBeep.IsEnabled = ok; BtnScreenshot.IsEnabled = ok; BtnRecord.IsEnabled = ok;
            if (!ok) {
                if (_isRecording) StopRec();
                VideoImage.Visibility = Visibility.Collapsed; NoVideoTip.Visibility = Visibility.Visible;
                TxtFps.Text = "-- FPS"; TxtTemp.Text = "-- ℃";
                _latestSoftwareBitmap?.Dispose(); _latestSoftwareBitmap = null; _latestFrameData = null;
            }
        });
    }

    private void OnVideoFrame(byte[] data) {
        _latestFrameData = data;
        if (_isDecodingFrame) return;
        _isDecodingFrame = true;
        _dispatcherQueue.TryEnqueue(async () => {
            try {
                using var s = new InMemoryRandomAccessStream();
                using var w = new DataWriter(s);
                w.WriteBytes(_latestFrameData ?? data);
                await w.StoreAsync();
                await w.FlushAsync();
                s.Seek(0);
                var bitmapImage = new BitmapImage();
                await bitmapImage.SetSourceAsync(s);
                VideoImage.Source = bitmapImage;
                if (VideoImage.Visibility == Visibility.Collapsed) { VideoImage.Visibility = Visibility.Visible; NoVideoTip.Visibility = Visibility.Collapsed; }
                if (_isRecording) {
                    _recordedFrames.Add((byte[])data.Clone());
                    if (_recordTempDir != null) {
                        var framePath = System.IO.Path.Combine(_recordTempDir, $"frame_{_recordFrameCount:D6}.jpg");
                        System.IO.File.WriteAllBytes(framePath, data);
                        _recordFrameCount++;
                    }
                }
            } catch { } finally { _isDecodingFrame = false; }
        });
    }

    private void OnStatus(DeviceStatusData st) {
        _dispatcherQueue.TryEnqueue(() => {
            if (st.BrightnessPercent > 0 || st.Temperature > 0 || st.Temperature == -1) {
                TxtBright.Text = $"{st.BrightnessPercent} %";
                TxtTemp.Text = st.Temperature == -1 ? "ERR" : $"{st.Temperature} ℃";
            }
            if (st.IsInitial) SliderLed.Value = st.BrightnessPercent;
        });
    }

    private void BtnDisconnect_Click(object s, RoutedEventArgs e) => _bleService.Disconnect();
    private void BtnBeep_Click(object s, RoutedEventArgs e) => _bleService.SendBeepCmd();
    private void SliderLed_ValueChanged(object s, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e) {
        var v = (int)SliderLed.Value; TxtBright.Text = $"{v} %"; SliderValDisplay.Text = $"{v}%"; _bleService.SendLedBrightnessCmd(v);
    }

    private async void BtnScreenshot_Click(object s, RoutedEventArgs e) {
        if (_latestFrameData == null) return;
        try {
            var pk = new Windows.Storage.Pickers.FileSavePicker(); pk.FileTypeChoices.Add("JPEG", new[] { ".jpg" });
            pk.SuggestedFileName = $"ESPAuto_SNAP_{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";
            WinRT.Interop.InitializeWithWindow.Initialize(pk, WinRT.Interop.WindowNative.GetWindowHandle(this));
            var f = await pk.PickSaveFileAsync();
            if (f != null) { using var st = await f.OpenAsync(FileAccessMode.ReadWrite); using var wr = new DataWriter(st); wr.WriteBytes(_latestFrameData); await wr.StoreAsync(); }
        } catch { }
    }

    private void BtnRecord_Click(object s, RoutedEventArgs e) { if (!_isRecording) StartRec(); else StopRec(); }
    private void StartRec() {
        if (_latestFrameData == null) return;
        _recordedFrames.Clear(); _isRecording = true; _recordStartTime = DateTime.Now;
        _recordFrameCount = 0;
        _recordTempDir = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"ESPAUTO_rec_{DateTime.Now:yyyyMMdd_HHmmss}");
        System.IO.Directory.CreateDirectory(_recordTempDir);
        BtnRecord.Background = new SolidColorBrush(Windows.UI.Color.FromArgb(64,255,69,58));
        LblRecord.Text = _i18n[_currentLang]["btnRecordStop"]; RecIndicator.Visibility = Visibility.Visible;
        _recordTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        _recordTimer.Tick += (s, e) => { var el = (int)(DateTime.Now - _recordStartTime).TotalSeconds; RecTimerText.Text = $"{(el/60):D2}:{(el%60):D2}"; };
        _recordTimer.Start();
    }
    private async void StopRec() {
        if (!_isRecording) return; _isRecording = false;
        _recordTimer?.Stop(); _recordTimer = null;
        BtnRecord.ClearValue(Control.BackgroundProperty);
        LblRecord.Text = _i18n[_currentLang]["btnRecord"]; RecIndicator.Visibility = Visibility.Collapsed; RecTimerText.Text = "00:00";

        var frameCount = _recordFrameCount;
        var tempDir = _recordTempDir;
        _recordedFrames.Clear();
        _recordTempDir = null;

        if (frameCount < 2 || tempDir == null || !System.IO.Directory.Exists(tempDir)) return;

        try {
            var picker = new Windows.Storage.Pickers.FileSavePicker();
            picker.FileTypeChoices.Add("MP4 视频", new[] { ".mp4" });
            picker.SuggestedFileName = $"ESPAUTO_{DateTime.Now:yyyyMMdd_HHmmss}";
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
            var outputFile = await picker.PickSaveFileAsync();
            if (outputFile == null) return;

            Log($"Exporting {frameCount} frames to MP4...");

            var composition = new Windows.Media.Editing.MediaComposition();
            var frameDuration = TimeSpan.FromMilliseconds(33);

            for (int i = 0; i < frameCount; i++) {
                var filePath = System.IO.Path.Combine(tempDir, $"frame_{i:D6}.jpg");
                if (!System.IO.File.Exists(filePath)) continue;
                var imgFile = await Windows.Storage.StorageFile.GetFileFromPathAsync(filePath);
                var clip = await Windows.Media.Editing.MediaClip.CreateFromImageFileAsync(imgFile, frameDuration);
                composition.Clips.Add(clip);
            }

            if (composition.Clips.Count == 0) return;

            var profile = Windows.Media.MediaProperties.MediaEncodingProfile.CreateMp4(Windows.Media.MediaProperties.VideoEncodingQuality.HD1080p);
            var result = await composition.RenderToFileAsync(outputFile, Windows.Media.Editing.MediaTrimmingPreference.Fast, profile);
            Log($"MP4 export result: {result}");

            try { System.IO.Directory.Delete(tempDir, true); } catch { }
        } catch (Exception ex) {
            Log($"MP4 export error: {ex}");
            try { if (tempDir != null) System.IO.Directory.Delete(tempDir, true); } catch { }
        }
    }

    private void RootGrid_PreviewKeyDown(object s, KeyRoutedEventArgs e) {
        if (!_bleService.IsConnected) return;
        var k = e.Key.ToString().ToLower();
        if (k == "i") {
            e.Handled = true;
            if (_servoTimer == null) { _bleService.SendServoUpCmd(); _servoTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) }; _servoTimer.Tick += (a, b) => _bleService.SendServoUpCmd(); _servoTimer.Start(); }
            return;
        }
        if (k == "k") {
            e.Handled = true;
            if (_servoTimer == null) { _bleService.SendServoDownCmd(); _servoTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) }; _servoTimer.Tick += (a, b) => _bleService.SendServoDownCmd(); _servoTimer.Start(); }
            return;
        }
        if (k == "space" && !e.KeyStatus.WasKeyDown) { e.Handled = true; _bleService.SendBeepCmd(); return; }
        if (!_keyStates.ContainsKey(k)) return;
        e.Handled = true;
        _keyStates[k] = true;
    }
    private void RootGrid_KeyDown(object s, KeyRoutedEventArgs e) { }
    private void RootGrid_KeyUp(object s, KeyRoutedEventArgs e) {
        if (!_bleService.IsConnected) return;
        var k = e.Key.ToString().ToLower();
        if (_keyStates.ContainsKey(k)) _keyStates[k] = false;
        if (k is "i" or "k") { _servoTimer?.Stop(); _servoTimer = null; }
    }
    private void ResetKeys() { foreach (var k in _keyStates.Keys) _keyStates[k] = false; _servoTimer?.Stop(); _servoTimer = null; }
    private void DriveTick() {
        if (!_bleService.IsConnected) return;
        sbyte sp = 0, st = 0;
        if (_keyStates["w"]) sp = -80; if (_keyStates["s"]) sp = 80;
        if (_keyStates["a"]) st = 80; if (_keyStates["d"]) st = -80;
        if (sp != 0 || st != 0) { _bleService.SendCarMoveCmd(sp, st); _lastSentSpeed = sp; _lastSentSteer = st; }
        else if (_lastSentSpeed != 0 || _lastSentSteer != 0) { _bleService.SendCarMoveCmd(0, 0); _lastSentSpeed = 0; _lastSentSteer = 0; }
    }
}
