/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/

import Foundation
import Combine
import AVFoundation
import CoreVideo
import AppKit
import ImageIO

class VideoRecorder: ObservableObject {
    var isRecording = false
    var recordingDuration: TimeInterval = 0

    private var frames: [Data] = []
    private var startTime: Date?
    private var timer: Timer?
    private let w = 640, h = 480

    func start() {
        guard !isRecording else { return }
        frames = []
        isRecording = true
        startTime = Date()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            if let s = self?.startTime { self?.recordingDuration = Date().timeIntervalSince(s) }
        }
    }

    func append(jpeg: Data) {
        guard isRecording else { return }
        frames.append(jpeg)
    }

    func stop() {
        guard isRecording else { return }
        timer?.invalidate(); timer = nil
        isRecording = false

        let capturedFrames = frames
        frames = []
        DispatchQueue.global(qos: .userInitiated).async {
            self.buildMP4(frames: capturedFrames)
        }
    }

    private func decodeJPEG(_ data: Data) -> CGImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(src, 0, nil)
    }

    private func showAlert(title: String, message: String) {
        DispatchQueue.main.async {
            let alert = NSAlert()
            alert.messageText = title
            alert.informativeText = message
            alert.alertStyle = .warning
            alert.addButton(withTitle: "确定")
            alert.runModal()
        }
    }

    private func buildMP4(frames: [Data]) {
        guard !frames.isEmpty else {
            showAlert(title: "录制失败", message: "没有捕获到视频帧，请确认设备已连接并有视频输出")
            return
        }

        var cgImages: [CGImage] = []
        for jpeg in frames {
            if let img = decodeJPEG(jpeg) { cgImages.append(img) }
        }

        guard !cgImages.isEmpty else {
            showAlert(title: "录制失败", message: "无法解码JPEG帧 (\(frames.count)帧)")
            return
        }

        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("ESPAUTO_rec_\(Int(Date().timeIntervalSince1970))")
        try? FileManager.default.createDirectory(at: tempURL, withIntermediateDirectories: true)
        let url = tempURL.appendingPathComponent("output.mp4")

        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else {
            showAlert(title: "录制失败", message: "无法创建视频写入器")
            return
        }

        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: w,
            AVVideoHeightKey: h,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: 4_000_000,
                AVVideoExpectedSourceFrameRateKey: 10
            ] as [String: Any]
        ]

        guard writer.canApply(outputSettings: settings, forMediaType: .video) else {
            showAlert(title: "录制失败", message: "不支持的视频编码设置")
            return
        }

        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = false

        guard writer.canAdd(input) else {
            showAlert(title: "录制失败", message: "无法添加视频输入轨道")
            return
        }
        writer.add(input)

        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: w,
                kCVPixelBufferHeightKey as String: h
            ]
        )

        guard writer.startWriting() else {
            let err = writer.error?.localizedDescription ?? "unknown"
            showAlert(title: "录制失败", message: "无法开始写入视频: \(err)")
            return
        }
        writer.startSession(atSourceTime: .zero)

        var writtenFrames = 0
        for (i, cg) in cgImages.enumerated() {
            while !input.isReadyForMoreMediaData {
                if writer.status == .failed { break }
                Thread.sleep(forTimeInterval: 0.01)
            }
            if writer.status == .failed { break }

            guard let pool = adaptor.pixelBufferPool else { break }

            var pb: CVPixelBuffer?
            let status = CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pb)
            guard status == kCVReturnSuccess, let buf = pb else { continue }

            CVPixelBufferLockBaseAddress(buf, [])
            let dst = CVPixelBufferGetBaseAddress(buf)
            let bpr = CVPixelBufferGetBytesPerRow(buf)
            guard let ctx = CGContext(data: dst, width: w, height: h, bitsPerComponent: 8,
                bytesPerRow: bpr, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else {
                CVPixelBufferUnlockBaseAddress(buf, [])
                continue
            }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            CVPixelBufferUnlockBaseAddress(buf, [])

            let time = CMTime(value: CMTimeValue(i), timescale: 10)
            adaptor.append(buf, withPresentationTime: time)
            writtenFrames += 1
        }

        guard writer.status != .failed, writtenFrames > 0 else {
            let err = writer.error?.localizedDescription ?? "unknown"
            showAlert(title: "录制失败", message: "视频写入失败: \(err)")
            return
        }

        input.markAsFinished()

        let semaphore = DispatchSemaphore(value: 0)
        writer.finishWriting { semaphore.signal() }
        semaphore.wait()

        if writer.status == .failed {
            let err = writer.error?.localizedDescription ?? "unknown"
            showAlert(title: "录制失败", message: "视频写入失败: \(err)")
            return
        }

        DispatchQueue.main.async {
            self.showSavePanel(url: url, tempDir: tempURL)
        }
    }

    private func showSavePanel(url: URL, tempDir: URL) {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.mpeg4Movie]
        panel.nameFieldStringValue = "ESPAUTO_\(Int(Date().timeIntervalSince1970)).mp4"
        panel.canCreateDirectories = true
        panel.prompt = "保存"

        if panel.runModal() == .OK, let dest = panel.url {
            do {
                try FileManager.default.copyItem(at: url, to: dest)
            } catch {
                showAlert(title: "保存失败", message: error.localizedDescription)
            }
        }

        try? FileManager.default.removeItem(at: tempDir)
    }

    var timeText: String { let t = Int(recordingDuration); return String(format: "%02d:%02d", t / 60, t % 60) }
}
