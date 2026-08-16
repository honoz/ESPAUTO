/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/
namespace ESPAUTO.Models;

/// <summary>
/// 设备状态数据
/// </summary>
public class DeviceStatusData
{
    /// <summary>灯光亮度 (0-100%)</summary>
    public int BrightnessPercent { get; set; }

    /// <summary>终端温度 (℃), -1 表示错误</summary>
    public int Temperature { get; set; }

    /// <summary>实时帧率</summary>
    public int Fps { get; set; }

    /// <summary>是否为连接时的初始读取（true=初始，false=轮询）</summary>
    public bool IsInitial { get; set; }
}

/// <summary>
/// BLE 设备信息（扫描结果）
/// </summary>
public class BleDeviceInfo
{
    public ulong Address { get; set; }
    public string Name { get; set; } = "";
    public short Rssi { get; set; }
    public string AddressText => $"{Address >> 40:X2}:{(Address >> 32) & 0xFF:X2}:{(Address >> 24) & 0xFF:X2}:{(Address >> 16) & 0xFF:X2}:{(Address >> 8) & 0xFF:X2}:{Address & 0xFF:X2}";
    /// <summary>信号强度百分比 (0-100%)</summary>
    public int SignalPercent
    {
        get
        {
            // RSSI 范围 -30(最强) 到 -100(最弱) 映射到 100% 到 0%
            int percent = (int)((Rssi + 100) * 100.0 / 70.0);
            return System.Math.Clamp(percent, 0, 100);
        }
    }
}

/// <summary>
/// 连接状态枚举
/// </summary>
public enum ConnectionStatus
{
    Offline,
    Searching,
    Linking,
    Online,
    Error
}
