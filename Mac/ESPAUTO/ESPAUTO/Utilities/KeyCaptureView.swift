/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/

import SwiftUI
import AppKit

/// 使用 NSEvent 全局事件监听捕获键盘事件，不依赖 SwiftUI 焦点系统
struct KeyCaptureView: NSViewRepresentable {
    let onKeyDown: (String) -> Void
    let onKeyUp: (String) -> Void

    func makeNSView(context: Context) -> NSView {
        let view = KeyView()
        view.onKeyDown = onKeyDown
        view.onKeyUp = onKeyUp
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {}

    class KeyView: NSView {
        var onKeyDown: ((String) -> Void)?
        var onKeyUp: ((String) -> Void)?
        private var monitors: [Any] = []

        override var acceptsFirstResponder: Bool { true }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            monitors.forEach { NSEvent.removeMonitor($0) }
            monitors.removeAll()

            let m1 = NSEvent.addLocalMonitorForEvents(matching: .keyDown, handler: { [weak self] event in
                guard let self = self else { return event }
                let chars = event.charactersIgnoringModifiers ?? ""
                self.onKeyDown?(Self.keyString(chars: chars, keyCode: event.keyCode))
                return event
            })
            if let m = m1 { monitors.append(m) }

            let m2 = NSEvent.addLocalMonitorForEvents(matching: .keyUp, handler: { [weak self] event in
                guard let self = self else { return event }
                let chars = event.charactersIgnoringModifiers ?? ""
                self.onKeyUp?(Self.keyString(chars: chars, keyCode: event.keyCode))
                return event
            })
            if let m = m2 { monitors.append(m) }

            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                self?.window?.makeFirstResponder(self)
            }
        }

        override func keyDown(with event: NSEvent) {
            let chars = event.charactersIgnoringModifiers ?? ""
            onKeyDown?(Self.keyString(chars: chars, keyCode: event.keyCode))
        }

        override func keyUp(with event: NSEvent) {
            let chars = event.charactersIgnoringModifiers ?? ""
            onKeyUp?(Self.keyString(chars: chars, keyCode: event.keyCode))
        }

        static func keyString(chars: String, keyCode: UInt16) -> String {
            switch keyCode {
            case 126: return "w"  // up arrow
            case 125: return "s"  // down arrow
            case 123: return "a"  // left arrow
            case 124: return "d"  // right arrow
            default: return chars.lowercased()
            }
        }

        deinit {
            monitors.forEach { NSEvent.removeMonitor($0) }
        }
    }
}
