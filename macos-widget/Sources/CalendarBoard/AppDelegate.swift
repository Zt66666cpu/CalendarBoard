import AppKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private let timeline = IslandTimeline()
    private var webBridge: WebBridge!
    private var islandController: IslandController!
    private let hotkeys = Hotkeys()
    private var statusMenu: StatusMenu!

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        webBridge = WebBridge(timeline: timeline)
        islandController = IslandController(timeline: timeline, webBridge: webBridge)
        webBridge.delegate = islandController

        timeline.onCompactStateChanged = { [weak self] _ in
            self?.islandController.refreshCompactOrHidden()
        }
        timeline.onMidnightRollover = { [weak self] in
            self?.webBridge.triggerSync()
        }
        timeline.onPeriodicSync = { [weak self] in
            self?.webBridge.triggerSync()
        }
        timeline.onPromptRequested = { [weak self] text, targetSurface in
            self?.islandController.showPrompt(text: text, targetSurface: targetSurface)
        }

        hotkeys.register(
            onQuickAdd: { [weak self] in
                self?.islandController.openQuickAdd()
            },
            onToggleIsland: { [weak self] in
                self?.islandController.toggleFromHotkeyOrMenu()
            }
        )

        statusMenu = StatusMenu(
            islandController: islandController,
            webBridge: webBridge,
            hotkeys: hotkeys
        )
        islandController.onStateChanged = { [weak self] in
            guard let self else { return }
            self.statusMenu.refreshState(isExpanded: self.islandController.mode == .expanded)
        }

        webBridge.loadUI()
        timeline.startTimers()
        islandController.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        timeline.stopTimers()
    }
}
