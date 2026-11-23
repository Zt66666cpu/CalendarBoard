import AppKit

@MainActor
final class StatusMenu: NSObject {
    private let statusItem: NSStatusItem
    private let toggleMenuItem = NSMenuItem()
    private let islandController: IslandController
    private let webBridge: WebBridge
    private let hotkeys: Hotkeys

    init(islandController: IslandController, webBridge: WebBridge, hotkeys: Hotkeys) {
        self.islandController = islandController
        self.webBridge = webBridge
        self.hotkeys = hotkeys
        self.statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)

        super.init()

        configureStatusItem()
        configureMenu()
    }

    func refreshState(isExpanded: Bool) {
        toggleMenuItem.title = isExpanded ? "收起课程岛" : "展开课程岛"
    }

    private func configureStatusItem() {
        guard let button = statusItem.button else { return }
        let image = NSImage(systemSymbolName: "calendar", accessibilityDescription: "我的课表日历")
        image?.isTemplate = true
        button.image = image
        button.toolTip = "我的课表日历"
    }

    private func configureMenu() {
        let menu = NSMenu()

        toggleMenuItem.title = "展开课程岛"
        toggleMenuItem.target = self
        toggleMenuItem.action = #selector(handleToggle)
        menu.addItem(toggleMenuItem)

        let quickAddItem = NSMenuItem(
            title: "快速添加…",
            action: #selector(handleQuickAdd),
            keyEquivalent: ""
        )
        quickAddItem.target = self
        menu.addItem(quickAddItem)

        menu.addItem(.separator())

        let syncItem = NSMenuItem(
            title: "立即同步",
            action: #selector(handleSync),
            keyEquivalent: "r"
        )
        syncItem.target = self
        menu.addItem(syncItem)

        let hotkeysItem = NSMenuItem(
            title: "快捷键…",
            action: #selector(handleShowHotkeys),
            keyEquivalent: ""
        )
        hotkeysItem.target = self
        menu.addItem(hotkeysItem)

        let settingsItem = NSMenuItem(
            title: "设置与云端连接…",
            action: #selector(handleOpenSettings),
            keyEquivalent: ","
        )
        settingsItem.target = self
        menu.addItem(settingsItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(
            title: "退出 CalendarBoard",
            action: #selector(handleQuit),
            keyEquivalent: "q"
        )
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem.menu = menu
    }

    @objc private func handleToggle() {
        islandController.toggleFromHotkeyOrMenu()
    }

    @objc private func handleQuickAdd() {
        islandController.openQuickAdd()
    }

    @objc private func handleSync() {
        webBridge.triggerSync()
    }

    @objc private func handleShowHotkeys() {
        hotkeys.showRecorderWindow()
    }

    @objc private func handleOpenSettings() {
        islandController.openSettings()
    }

    @objc private func handleQuit() {
        NSApp.terminate(nil)
    }
}
