import AppKit
import KeyboardShortcuts
import SwiftUI

extension KeyboardShortcuts.Name {
    static let quickAdd = Self("quickAdd", default: .init(.a, modifiers: [.control, .option]))
    static let toggleIsland = Self("toggleIsland", default: .init(.c, modifiers: [.control, .option]))
}

@MainActor
final class Hotkeys {
    private var recorderWindow: NSWindow?

    func register(
        onQuickAdd: @escaping @MainActor () -> Void,
        onToggleIsland: @escaping @MainActor () -> Void
    ) {
        KeyboardShortcuts.onKeyUp(for: .quickAdd) {
            Task { @MainActor in
                onQuickAdd()
            }
        }
        KeyboardShortcuts.onKeyUp(for: .toggleIsland) {
            Task { @MainActor in
                onToggleIsland()
            }
        }
    }

    func showRecorderWindow() {
        if let recorderWindow {
            recorderWindow.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 360, height: 150),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "快捷键设置"
        window.isReleasedWhenClosed = false

        let content = Form {
            KeyboardShortcuts.Recorder("快速添加:", name: .quickAdd)
            KeyboardShortcuts.Recorder("展开 / 收起课程岛:", name: .toggleIsland)
        }
        .formStyle(.grouped)
        .frame(width: 340, height: 120)

        window.contentView = NSHostingView(rootView: content)
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        recorderWindow = window
    }
}
