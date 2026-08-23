/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/

import Foundation
import Combine

class KeyEventHandler: ObservableObject {
    private var keyStates = ["w": false, "s": false, "a": false, "d": false]
    private var driveTimer: Timer?
    private var servoTimer: Timer?
    private var lastSpeed: Int8 = 0, lastSteer: Int8 = 0
    weak var ble: EspAutoBleService?
    var isActive: Bool { ble?.isConnected ?? false }

    init() {
        driveTimer = Timer.scheduledTimer(withTimeInterval: 0.04, repeats: true) { [weak self] _ in self?.tick() }
        NotificationCenter.default.addObserver(forName: .windowBecameInactive, object: nil, queue: .main) { [weak self] _ in
            self?.resetKeys(); self?.ble?.forceStopCar()
        }
    }

    func keyDown(_ key: String) {
        guard isActive else { return }
        let k = key.lowercased()
        if k == "i" { if servoTimer == nil { ble?.sendServoUpCmd(); servoTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in self?.ble?.sendServoUpCmd() } }; return }
        if k == "k" { if servoTimer == nil { ble?.sendServoDownCmd(); servoTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in self?.ble?.sendServoDownCmd() } }; return }
        if k == " " { ble?.sendBeepCmd(); return }
        if keyStates.keys.contains(k) { keyStates[k] = true }
    }

    func keyUp(_ key: String) {
        guard isActive else { return }
        let k = key.lowercased()
        if keyStates.keys.contains(k) { keyStates[k] = false }
        if k == "i" || k == "k" { servoTimer?.invalidate(); servoTimer = nil }
    }

    func resetKeys() {
        keyStates.keys.forEach { keyStates[$0] = false }
        servoTimer?.invalidate(); servoTimer = nil
    }

    private func tick() {
        guard isActive else { return }
        var sp: Int8 = 0, st: Int8 = 0
        if keyStates["w"] == true { sp = -80 }; if keyStates["s"] == true { sp = 80 }
        if keyStates["a"] == true { st = 80 }; if keyStates["d"] == true { st = -80 }
        if sp != 0 || st != 0 { ble?.sendCarMoveCmd(speed: sp, steer: st); lastSpeed = sp; lastSteer = st }
        else if lastSpeed != 0 || lastSteer != 0 { ble?.sendCarMoveCmd(speed: 0, steer: 0); lastSpeed = 0; lastSteer = 0 }
    }

    deinit { driveTimer?.invalidate(); servoTimer?.invalidate() }
}

extension Notification.Name {
    static let windowBecameInactive = Notification.Name("windowBecameInactive")
}
