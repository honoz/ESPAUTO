/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/

import Foundation

class Localization: ObservableObject {
    static let shared = Localization()
    @Published var currentLang: String
    var isZh: Bool { currentLang == "zh-CN" }

    private let i18n: [String: [String: String]] = [
        "zh-CN": [
            "statusOnline": "已连接", "statusOffline": "未连接",
            "statusSearching": "正在搜索", "statusLinking": "正在配对", "statusError": "连接异常",
            "videoTip": "等待无线图传建立...", "btnDisconnect": "断开",
            "lblLink": "连接状态", "lblFps": "实时帧率", "lblTemp": "终端温度", "lblBright": "照明功率",
            "sliderLed": "灯光控制", "btnBeep": "鸣笛", "btnSnap": "快照",
            "btnRecord": "录制", "btnRecordStop": "停止",
            "guideTitle": "操控集群说明:",
            "guideMove": "车身移动：W (前) / S (后) / A (左旋) / D (右旋)",
            "guideServo": "精密云台：I (抬起) / K (降下)",
            "guideBeep": "应答模块：Space (短促鸣笛)",
            "guideTip": "提示：遥控前请确保窗口处于激活状态。",
            "scanTitle": "设备扫描", "scanStatus": "点击扫描按钮搜索附近的低功耗蓝牙设备",
            "scanBtn": "开始扫描", "scanStop": "停止扫描",
            "noDevice": "暂无设备", "connectSelected": "连接选中设备",
        ],
        "en": [
            "statusOnline": "ONLINE", "statusOffline": "OFFLINE",
            "statusSearching": "SEARCHING", "statusLinking": "PAIRING", "statusError": "CONNECTION ERROR",
            "videoTip": "Establishing wireless video link...", "btnDisconnect": "Disconnect",
            "lblLink": "Connection", "lblFps": "Real-time FPS", "lblTemp": "Device Temp", "lblBright": "LED Power",
            "sliderLed": "Light Control", "btnBeep": "Horn", "btnSnap": "Snapshot",
            "btnRecord": "Record", "btnRecordStop": "Stop",
            "guideTitle": "Cluster Control Guide:",
            "guideMove": "Chassis: W (Fwd) / S (Bwd) / A (Left) / D (Right)",
            "guideServo": "Gimbal: I (Tilt Up) / K (Tilt Down)",
            "guideBeep": "Response Module: Space (Short Honk)",
            "guideTip": "Tip: Ensure the window is focused before controlling.",
            "scanTitle": "Device Scan", "scanStatus": "Click scan to search for nearby BLE devices",
            "scanBtn": "Start Scan", "scanStop": "Stop Scan",
            "noDevice": "No devices found", "connectSelected": "Connect Selected Device",
        ]
    ]

    init() {
        let sys = Locale.current.language.languageCode?.identifier ?? "en"
        currentLang = sys.lowercased().hasPrefix("zh") ? "zh-CN" : "en"
    }

    func t(_ key: String) -> String {
        i18n[currentLang]?[key] ?? i18n["en"]?[key] ?? key
    }
}
