/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/

import Foundation

struct DeviceStatusData {
    var brightnessPercent: Int = 0
    var temperature: Int = 0
    var fps: Int = 0
    var isInitial: Bool = false
}

struct BleDeviceInfo: Identifiable, Equatable {
    let id: UInt64
    var address: UInt64 { id }
    var name: String = ""
    var rssi: Int16 = -100

    var addressText: String {
        let bytes = [(address >> 40) & 0xFF, (address >> 32) & 0xFF, (address >> 24) & 0xFF,
                     (address >> 16) & 0xFF, (address >> 8) & 0xFF, address & 0xFF]
        return bytes.map { String(format: "%02X", $0) }.joined(separator: ":")
    }

    var signalPercent: Int {
        max(0, min(100, Int(Double(rssi + 100) * 100.0 / 70.0)))
    }

    static func == (lhs: BleDeviceInfo, rhs: BleDeviceInfo) -> Bool { lhs.id == rhs.id }
}

enum ConnectionStatus {
    case offline, searching, linking, online, error
}
