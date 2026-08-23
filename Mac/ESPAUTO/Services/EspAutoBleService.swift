/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/

import Foundation
import CoreBluetooth
import Combine

class EspAutoBleService: NSObject, ObservableObject {
    // BLE UUID
    static let serviceUUID = CBUUID(string: "0000FFE0-0000-1000-8000-00805F9B34FB")
    static let controlCharUUID = CBUUID(string: "0000FFE2-0000-1000-8000-00805F9B34FB")
    static let videoCharUUID = CBUUID(string: "0000FFE1-0000-1000-8000-00805F9B34FB")
    static let statusCharUUID = CBUUID(string: "0000FFE3-0000-1000-8000-00805F9B34FB")

    // 命令码
    static let cmdLedBrightness: UInt8 = 0x00
    static let cmdBeep: UInt8 = 0x12
    static let cmdServoUp: UInt8 = 0x13
    static let cmdServoDown: UInt8 = 0x14
    static let cmdCarMove: UInt8 = 0x15

    // 帧回调（绕过SwiftUI onChange，确保每帧都被捕获）
    var onFrameReceived: ((Data) -> Void)?

    // Published 状态
    @Published var isConnected = false
    @Published var isScanning = false
    @Published var connectionStatus: ConnectionStatus = .offline
    @Published var discoveredDevices: [BleDeviceInfo] = []
    @Published var latestVideoFrame: Data?
    @Published var currentFps = 0
    @Published var deviceStatus = DeviceStatusData()
    @Published var initialBrightness: Int?

    // BLE 内部
    private var centralManager: CBCentralManager!
    private var connectedPeripheral: CBPeripheral?
    private var controlChar: CBCharacteristic?
    private var videoChar: CBCharacteristic?
    private var statusChar: CBCharacteristic?

    // 命令队列
    private var cmdQueue = [[UInt8]]()
    private var isSendingCmd = false
    private var cmdTimer: Timer?
    private var statusPollTimer: Timer?

    // 视频帧组装
    private var videoBuf = [UInt8](repeating: 0, count: 65536)
    private var videoBufLen = 0
    private var expectLen = 0
    private var brightnessSynced = false

    // FPS
    private var fpsCount = 0
    private var fpsLastTime = Date()

    // 扫描
    private var discoveredPeripherals = [UInt64: CBPeripheral]()
    private var pendingAddr: UInt64 = 0

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: .main)
        cmdTimer = Timer.scheduledTimer(withTimeInterval: 0.015, repeats: true) { [weak self] _ in
            self?.processNextCmd()
        }
        fpsLastTime = Date()
    }

    // MARK: - 扫描
    func startScan() {
        guard !isScanning else { return }
        isScanning = true
        discoveredPeripherals.removeAll()
        discoveredDevices.removeAll()
        centralManager.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func stopScan() {
        guard isScanning else { return }
        centralManager.stopScan()
        isScanning = false
    }

    // MARK: - 连接
    func connectToDevice(address: UInt64) {
        stopScan()
        cleanupBle()
        connectionStatus = .linking
        pendingAddr = address
        if let p = discoveredPeripherals[address] {
            connectedPeripheral = p
            p.delegate = self
            centralManager.connect(p, options: nil)
        } else {
            centralManager.scanForPeripherals(withServices: nil, options: nil)
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
                guard let self, self.connectionStatus == .linking else { return }
                self.centralManager.stopScan()
                self.isScanning = false
                self.connectionStatus = .error
            }
        }
    }

    func disconnect() {
        if let p = connectedPeripheral, isConnected {
            forceStopCar()
            centralManager.cancelPeripheralConnection(p)
        }
        cleanupBle()
        isConnected = false
        connectionStatus = .offline
    }

    // MARK: - 命令
    func sendCarMoveCmd(speed: Int8, steer: Int8) {
        addCmd([0xAA, 0xFF, Self.cmdCarMove, UInt8(bitPattern: speed), UInt8(bitPattern: steer)])
    }
    func sendLedBrightnessCmd(_ pct: Int) {
        addCmd([0xAA, 0xFF, Self.cmdLedBrightness, UInt8(round(Double(pct) / 100.0 * 255.0))])
    }
    func sendBeepCmd() { addCmd([0xAA, 0xFF, Self.cmdBeep]) }
    func sendServoUpCmd() { addCmd([0xAA, 0xFF, Self.cmdServoUp]) }
    func sendServoDownCmd() { addCmd([0xAA, 0xFF, Self.cmdServoDown]) }
    func forceStopCar() {
        if let c = controlChar, let p = connectedPeripheral {
            p.writeValue(Data([0xAA, 0xFF, Self.cmdCarMove, 0, 0]), for: c, type: .withResponse)
        }
    }

    private func addCmd(_ cmd: [UInt8]) {
        guard controlChar != nil else { return }
        if cmdQueue.count > 2 && cmd.count >= 3 && cmd[2] == Self.cmdCarMove {
            cmdQueue.removeAll { $0.count >= 3 && $0[2] == Self.cmdCarMove }
        }
        cmdQueue.append(cmd)
    }

    private func processNextCmd() {
        guard !isSendingCmd, let c = controlChar, let p = connectedPeripheral, !cmdQueue.isEmpty else { return }
        let cmd = cmdQueue.removeFirst()
        isSendingCmd = true
        p.writeValue(Data(cmd), for: c, type: .withResponse)
        isSendingCmd = false
    }

    // MARK: - 视频帧
    private func handleVideo(_ data: Data) {
        let b = [UInt8](data)
        guard b.count >= 2 else { return }
        if b.count >= 4 && b[0] == 0xAB && b[1] == 0xCD {
            expectLen = (Int(b[2]) << 8) | Int(b[3])
            videoBufLen = 0
            if b.count > 4 {
                let ov = b.count - 4
                let cp = min(ov, expectLen)
                if cp > 0 && expectLen <= videoBuf.count {
                    for i in 0..<cp { videoBuf[i] = b[4 + i] }
                    videoBufLen = cp
                }
                if videoBufLen >= expectLen { emitFrame() }
            }
            return
        }
        guard expectLen > 0 && videoBufLen < expectLen else { return }
        let cp = min(b.count, expectLen - videoBufLen)
        if cp > 0 && videoBufLen + cp <= videoBuf.count {
            for i in 0..<cp { videoBuf[videoBufLen + i] = b[i] }
            videoBufLen += cp
        }
        if videoBufLen >= expectLen { emitFrame() }
    }

    private func emitFrame() {
        guard expectLen >= 2, videoBuf[0] == 0xFF, videoBuf[1] == 0xD8 else { resetFrame(); return }
        let frame = Data(videoBuf[0..<expectLen])
        fpsCount += 1
        let now = Date()
        if now.timeIntervalSince(fpsLastTime) >= 1.0 {
            currentFps = fpsCount; fpsCount = 0; fpsLastTime = now
        }
        latestVideoFrame = frame
        onFrameReceived?(frame)
        resetFrame()
    }

    private func resetFrame() { expectLen = 0; videoBufLen = 0 }

    // MARK: - 状态
    private func handleStatus(_ data: Data, isInitial: Bool) {
        guard !data.isEmpty else { return }
        let b = [UInt8](data)
        var s = DeviceStatusData()
        s.isInitial = isInitial
        s.brightnessPercent = Int(round(Double(b[0]) / 255.0 * 100.0))
        if b.count >= 2 { s.temperature = b[1] == 0xFF ? -1 : Int(b[1]) }
        deviceStatus = s
    }

    // MARK: - 清理
    private func cleanupBle() {
        statusPollTimer?.invalidate(); statusPollTimer = nil
        if let c = videoChar, let p = connectedPeripheral { p.setNotifyValue(false, for: c) }
        controlChar = nil; videoChar = nil; statusChar = nil; connectedPeripheral = nil
        cmdQueue.removeAll(); videoBufLen = 0; expectLen = 0
        brightnessSynced = false; initialBrightness = nil
    }
}

// MARK: - CBCentralManagerDelegate
extension EspAutoBleService: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn && pendingAddr != 0 {
            centralManager.scanForPeripherals(withServices: nil, options: nil)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                         advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard let mfg = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
              mfg.count >= 2 else { return }
        let companyId = UInt16(mfg[0]) | (UInt16(mfg[1]) << 8)
        guard companyId == 0xE5E5 else { return }

        let addrKey = UInt64(truncatingIfNeeded: peripheral.identifier.hashValue)
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? "")
            .isEmpty ? "ESPAUTO (\(String(format: "%X", UInt64(addrKey & 0xFFFFFFFFFFFF))))"
            : advertisementData[CBAdvertisementDataLocalNameKey] as! String
        let rssiVal = Int16(truncating: RSSI)

        if let idx = discoveredDevices.firstIndex(where: { $0.name == name }) {
            discoveredDevices[idx] = BleDeviceInfo(id: addrKey, name: name, rssi: rssiVal)
        } else {
            discoveredDevices.append(BleDeviceInfo(id: addrKey, name: name, rssi: rssiVal))
        }
        discoveredPeripherals[addrKey] = peripheral

        if pendingAddr != 0 {
            centralManager.stopScan()
            isScanning = false
            connectedPeripheral = peripheral
            peripheral.delegate = self
            centralManager.connect(peripheral, options: nil)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectionStatus = .error; pendingAddr = 0
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        cleanupBle(); isConnected = false; connectionStatus = .offline
    }
}

// MARK: - CBPeripheralDelegate
extension EspAutoBleService: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let svc = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            connectionStatus = .error; return
        }
        peripheral.discoverCharacteristics([Self.controlCharUUID, Self.videoCharUUID, Self.statusCharUUID], for: svc)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }
        for c in chars {
            switch c.uuid {
            case Self.controlCharUUID: controlChar = c
            case Self.videoCharUUID:
                videoChar = c; peripheral.setNotifyValue(true, for: c)
            case Self.statusCharUUID:
                statusChar = c; peripheral.readValue(for: c)
                statusPollTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
                    if let s = self?.statusChar, self?.isConnected == true {
                        self?.connectedPeripheral?.readValue(for: s)
                    }
                }
            default: break
            }
        }
        if controlChar != nil {
            connectionStatus = .online; isConnected = true; pendingAddr = 0
        } else { connectionStatus = .error }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        if characteristic.uuid == Self.videoCharUUID { handleVideo(data) }
        else if characteristic.uuid == Self.statusCharUUID {
            handleStatus(data, isInitial: !brightnessSynced)
            if !brightnessSynced { brightnessSynced = true; initialBrightness = deviceStatus.brightnessPercent }
        }
    }
}
