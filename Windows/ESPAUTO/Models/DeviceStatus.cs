/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/
namespace ESPAUTO.Models;

public class DeviceStatusData
{
    public int BrightnessPercent { get; set; }

    public int Temperature { get; set; }

    public int Fps { get; set; }

    public bool IsInitial { get; set; }
}

public class BleDeviceInfo
{
    public ulong Address { get; set; }
    public string Name { get; set; } = "";
    public short Rssi { get; set; }
    public string AddressText => $"{Address >> 40:X2}:{(Address >> 32) & 0xFF:X2}:{(Address >> 24) & 0xFF:X2}:{(Address >> 16) & 0xFF:X2}:{(Address >> 8) & 0xFF:X2}:{Address & 0xFF:X2}";
    public int SignalPercent
    {
        get
        {
            int percent = (int)((Rssi + 100) * 100.0 / 70.0);
            return System.Math.Clamp(percent, 0, 100);
        }
    }
}

public enum ConnectionStatus
{
    Offline,
    Searching,
    Linking,
    Online,
    Error
}
