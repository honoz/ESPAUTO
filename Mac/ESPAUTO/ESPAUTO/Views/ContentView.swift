/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/

import SwiftUI

struct ContentView: View {
    @StateObject var ble = EspAutoBleService()
    @StateObject var lang = Localization.shared
    @StateObject var recorder = VideoRecorder()
    @StateObject var keyHandler = KeyEventHandler()

    @State var selectedDevice: BleDeviceInfo?
    @State var sliderVal: Double = 0
    @State var hasVideo = false
    @State var isRecording = false
    @State var recTimeText = "00:00"
    @State private var rightHeight: CGFloat = 0
    private var recTimer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()

    private let rightColumnWidth: CGFloat = 460

    var body: some View {
        GlassEffectContainer(spacing: 16) {
            HStack(alignment: .top, spacing: 20) {
                scanPanel
                    .frame(minWidth: 280, idealWidth: 320)
                    .frame(height: rightHeight > 0 ? rightHeight : nil)
                VStack(spacing: 12) {
                    videoArea
                    statusCards
                    controlButtons
                    lightControl
                    controlGuide
                }
                .frame(width: rightColumnWidth)
                .frame(maxHeight: .infinity)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { h in
                    if abs(rightHeight - h) > 0.5 { rightHeight = h }
                }
            }
            .padding(20)
        }
        .background(Color(nsColor: .windowBackgroundColor).ignoresSafeArea())
        .onAppear {
            keyHandler.ble = ble
            ble.onFrameReceived = { [weak recorder] frame in
                recorder?.append(jpeg: frame)
            }
        }
        .overlay(KeyCaptureView(
            onKeyDown: { key in keyHandler.keyDown(key) },
            onKeyUp: { key in keyHandler.keyUp(key) }
        ).frame(width: 0, height: 0))
        .onChange(of: ble.isConnected) { _, ok in
            if !ok { keyHandler.resetKeys(); hasVideo = false; if isRecording { stopRec() } }
        }
        .onChange(of: ble.latestVideoFrame) { _, frame in
            if frame != nil { hasVideo = true }
        }
        .onChange(of: ble.initialBrightness) { _, v in if let v { sliderVal = Double(v) } }
        .onReceive(recTimer) { _ in if isRecording { recTimeText = recorder.timeText } }
    }

    private var scanPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(lang.t("scanTitle")).font(.system(size: 16, weight: .semibold))

            Button(action: { ble.isScanning ? ble.stopScan() : ble.startScan() }) {
                HStack(spacing: 8) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                    Text(ble.isScanning ? lang.t("scanStop") : lang.t("scanBtn"))
                }.frame(maxWidth: .infinity).frame(height: 40)
            }.buttonStyle(.glassProminent).tint(.accentColor)

            Text(scanStatus).font(.system(size: 12)).foregroundStyle(.secondary)

            ScrollView {
                LazyVStack(spacing: 4) {
                    if ble.discoveredDevices.isEmpty {
                        Text(lang.t("noDevice")).font(.system(size: 13)).foregroundStyle(.tertiary)
                            .frame(maxWidth: .infinity).padding(.vertical, 40)
                    } else {
                        ForEach(ble.discoveredDevices) { d in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(d.name).font(.system(size: 13, weight: .medium)).lineLimit(1).truncationMode(.tail)
                                    Text(d.addressText).font(.system(size: 11)).foregroundStyle(.tertiary)
                                }
                                Spacer()
                                Text("\(d.signalPercent)%").font(.system(size: 12)).foregroundStyle(.secondary)
                            }
                            .padding(.horizontal, 12).padding(.vertical, 10)
                            .background(selectedDevice?.id == d.id ? Color.accentColor.opacity(0.15) : Color.primary.opacity(0.04),
                                        in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                            .onTapGesture { selectedDevice = d }
                        }
                    }
                }
            }.frame(maxHeight: .infinity)

            Button(action: {
                if ble.isConnected { ble.disconnect() }
                else if let d = selectedDevice { ble.connectToDevice(address: d.id) }
            }) {
                HStack(spacing: 8) {
                    Image(systemName: ble.isConnected ? "xmark" : "link")
                    Text(ble.isConnected ? lang.t("btnDisconnect") : lang.t("connectSelected"))
                }.frame(maxWidth: .infinity).frame(height: 44)
            }.buttonStyle(.glassProminent)
            .tint(ble.isConnected ? .red : .accentColor)
            .disabled(!ble.isConnected && selectedDevice == nil)
        }
        .padding(20).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.ultraThinMaterial))
    }

    private var videoArea: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.black)

            if hasVideo, let data = ble.latestVideoFrame, let img = NSImage(data: data) {
                Image(nsImage: img).resizable().aspectRatio(contentMode: .fill).clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            } else {
                VStack(spacing: 16) {
                    Image(systemName: "video.slash").font(.system(size: 48)).foregroundStyle(Color(white: 0.33))
                    Text(lang.t("videoTip")).font(.system(size: 13)).foregroundStyle(Color(white: 0.53))
                }
            }
            if isRecording {
                HStack(spacing: 6) {
                    Circle().fill(.red).frame(width: 7, height: 7)
                    Text("REC \(recTimeText)").font(.system(size: 11, weight: .semibold)).foregroundStyle(.white)
                }.padding(.horizontal, 12).padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.black.opacity(0.6)))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding(16)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: rightColumnWidth * 3.0 / 4.0)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var statusCards: some View {
        HStack(spacing: 10) {
            statusCard(label: lang.t("lblLink"), value: stateText, color: stateColor)
            statusCard(label: lang.t("lblFps"), value: ble.isConnected ? "\(ble.currentFps) FPS" : "-- FPS", color: .primary)
            statusCard(label: lang.t("lblTemp"), value: tempText, color: .primary)
            statusCard(label: lang.t("lblBright"), value: ble.isConnected ? "\(ble.deviceStatus.brightnessPercent) %" : "-- %", color: .primary)
        }
    }

    private func statusCard(label: String, value: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.system(size: 10)).foregroundStyle(.secondary)
            Text(value).font(.system(size: 15, weight: .bold)).foregroundColor(color)
                .lineLimit(1).minimumScaleFactor(0.7)
        }.frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(.ultraThinMaterial))
    }

    private var controlButtons: some View {
        HStack(spacing: 12) {
            Button(action: { ble.sendBeepCmd() }) {
                Text(lang.t("btnBeep")).font(.system(size: 14, weight: .semibold)).foregroundColor(.primary).frame(maxWidth: .infinity).frame(height: 36)
            }.buttonStyle(CardButtonStyle()).disabled(!ble.isConnected)

            Button(action: {
                guard let data = ble.latestVideoFrame else { return }
                let panel = NSSavePanel(); panel.allowedContentTypes = [.jpeg]
                panel.nameFieldStringValue = "ESPAuto_SNAP_\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
                if panel.runModal() == .OK, let url = panel.url { try? data.write(to: url) }
            }) {
                Text(lang.t("btnSnap")).font(.system(size: 14, weight: .semibold)).foregroundColor(.primary).frame(maxWidth: .infinity).frame(height: 36)
            }.buttonStyle(CardButtonStyle()).disabled(!ble.isConnected || !hasVideo)

            Button(action: { isRecording ? stopRec() : startRec() }) {
                HStack(spacing: 6) {
                    Circle().fill(isRecording ? .clear : Color.red)
                        .frame(width: 10, height: 10)
                        .overlay(Circle().stroke(Color.red, lineWidth: 1.5))
                    Text(isRecording ? lang.t("btnRecordStop") : lang.t("btnRecord"))
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.primary)
                }.frame(maxWidth: .infinity).frame(height: 36)
            }.buttonStyle(CardButtonStyle(tint: isRecording ? Color.red.opacity(0.25) : .clear))
            .disabled(!ble.isConnected || !hasVideo)
        }
    }

    private var lightControl: some View {
        VStack(spacing: 12) {
            HStack {
                Text(lang.t("sliderLed")).font(.system(size: 13, weight: .medium))
                Spacer()
                Text("\(Int(sliderVal))%").font(.system(size: 13, weight: .medium))
            }
            Slider(value: $sliderVal, in: 0...100)
                .disabled(!ble.isConnected)
        }.padding(16)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.ultraThinMaterial))
        .onChange(of: sliderVal) { _, v in ble.sendLedBrightnessCmd(Int(v.rounded())) }
    }

    private var controlGuide: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(lang.t("guideTitle")).font(.system(size: 14, weight: .semibold))
            guideRow(key: "W", text: lang.t("guideMove"))
            guideRow(key: "I", text: lang.t("guideServo"))
            guideRow(key: "␣", text: lang.t("guideBeep"))
            Text(lang.t("guideTip")).font(.system(size: 12)).foregroundStyle(.orange)
        }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding(16)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.ultraThinMaterial))
    }

    private func guideRow(key: String, text: String) -> some View {
        HStack(spacing: 12) {
            Text(key).font(.system(size: 12, weight: .bold))
                .frame(width: 28, height: 28)
                .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            Text(text).font(.system(size: 13))
        }
    }

    private var scanStatus: String {
        if ble.isScanning { return lang.isZh ? "正在扫描..." : "Scanning..." }
        if !ble.discoveredDevices.isEmpty {
            return lang.isZh ? "扫描完成，发现 \(ble.discoveredDevices.count) 个设备"
                : "Scan complete, \(ble.discoveredDevices.count) devices found"
        }
        return lang.t("scanStatus")
    }

    private var stateText: String {
        switch ble.connectionStatus {
        case .offline: lang.t("statusOffline"); case .searching: lang.t("statusSearching")
        case .linking: lang.t("statusLinking"); case .online: lang.t("statusOnline")
        case .error: lang.t("statusError")
        }
    }

    private var stateColor: Color {
        .primary
    }

    private var tempText: String {
        let t = ble.deviceStatus.temperature
        if t == 0 && !ble.isConnected { return "-- ℃" }
        return t == -1 ? "ERR" : "\(t) ℃"
    }

    private func startRec() {
        recorder.start(); isRecording = true; recTimeText = "00:00"
    }

    private func stopRec() {
        isRecording = false; recTimeText = "00:00"
        recorder.stop()
    }
}

private struct CardButtonStyle: ButtonStyle {
    var tint: Color = .clear
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(tint))
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Color.primary.opacity(0.08), lineWidth: 1))
            )
            .opacity(configuration.isPressed ? 0.7 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}
