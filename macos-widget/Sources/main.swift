import AppKit
import QuartzCore
import WebKit

private enum DockDisplayMode: String {
    case autoHide   // 平时缩成右侧极细液态玻璃条，鼠标贴边滑出
    case desktop    // 贴近桌面底层（Rainmeter 原版风格，不挡其他窗口）
    case normal     // 普通窗口
    case floating   // 置顶悬浮
}

private enum GlassAppearanceStyle: String {
    case regular
    case clear
}

private enum PanelState: String {
    case peek
    case sidebar
    case expanded
}

final class WidgetWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

final class HoverContainerView: NSView {
    var onMouseEntered: (() -> Void)?
    var onMouseExited: (() -> Void)?
    private var tracking: NSTrackingArea?

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let existing = tracking {
            removeTrackingArea(existing)
        }
        let area = NSTrackingArea(
            rect: bounds,
            options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(area)
        tracking = area
    }

    override func mouseEntered(with event: NSEvent) {
        super.mouseEntered(with: event)
        onMouseEntered?()
    }

    override func mouseExited(with event: NSEvent) {
        super.mouseExited(with: event)
        onMouseExited?()
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler, WKNavigationDelegate {
    private var window: WidgetWindow!
    private var containerView: HoverContainerView!
    private var webView: WKWebView!
    private var statusItem: NSStatusItem!

    // Dynamic glass backing (NSGlassEffectView on macOS 26+, fallback NSVisualEffectView)
    private var glassBackingView: NSView?

    private var expandMenuItem: NSMenuItem!
    private var peekMenuItem: NSMenuItem!
    private var hoverMenuItem: NSMenuItem!
    private var autoHideModeItem: NSMenuItem!
    private var desktopModeItem: NSMenuItem!
    private var normalModeItem: NSMenuItem!
    private var floatingModeItem: NSMenuItem!
    private var regularGlassItem: NSMenuItem!
    private var clearGlassItem: NSMenuItem!

    private let peekWidth: CGFloat = 18
    private let peekHeight: CGFloat = 156
    private let sidebarWidth: CGFloat = 356
    private let maxExpandedWidth: CGFloat = 1080
    private let outerMargin: CGFloat = 14

    private var panelState: PanelState = .sidebar
    private var isModalOpen = false
    private var isPinnedOpen = false
    private var hoverExpandEnabled = true
    private var dockMode: DockDisplayMode = .autoHide
    private var glassStyle: GlassAppearanceStyle = .regular

    private var hoverTimer: Timer?
    private var initialCollapseTimer: Timer?

    private let defaults = UserDefaults.standard
    private let storePrefix = "cb_store_"

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        if defaults.object(forKey: "cb_hover_expand") != nil {
            hoverExpandEnabled = defaults.bool(forKey: "cb_hover_expand")
        }
        if let rawMode = defaults.string(forKey: "cb_dock_mode"),
           let parsed = DockDisplayMode(rawValue: rawMode) {
            dockMode = parsed
        }
        if let rawGlass = defaults.string(forKey: "cb_glass_style"),
           let parsedGlass = GlassAppearanceStyle(rawValue: rawGlass) {
            glassStyle = parsedGlass
        }

        setupStatusBar()
        setupWindow()
        loadUI()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleScreenChange),
            name: NSApplication.didChangeScreenParametersNotification,
            object: nil
        )

        // Start visible in sidebar so user immediately sees the new Liquid Glass design;
        // in autoHide mode, if user doesn't hover it within 4.5s it gently tucks to the right edge.
        positionWindow(state: .sidebar, animated: false)
        window.makeKeyAndOrderFront(nil)
        if dockMode != .desktop {
            NSApp.activate(ignoringOtherApps: true)
        }

        if dockMode == .autoHide {
            initialCollapseTimer = Timer.scheduledTimer(withTimeInterval: 4.5, repeats: false) { [weak self] _ in
                guard let self = self,
                      self.dockMode == .autoHide,
                      !self.isModalOpen,
                      !self.isPinnedOpen else { return }
                let mouse = NSEvent.mouseLocation
                if !self.window.frame.insetBy(dx: -8, dy: -8).contains(mouse) {
                    self.positionWindow(state: .peek, animated: true)
                }
            }
        }
    }

    private func setupStatusBar() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let button = statusItem.button {
            if let image = NSImage(systemSymbolName: "calendar", accessibilityDescription: "课表日历") {
                image.isTemplate = true
                button.image = image
            } else {
                button.title = "📅"
            }
            button.toolTip = "我的课表日历 · Liquid Glass"
        }

        let menu = NSMenu()

        expandMenuItem = NSMenuItem(title: "展开完整月历", action: #selector(toggleExpandAction), keyEquivalent: "e")
        expandMenuItem.target = self
        menu.addItem(expandMenuItem)

        peekMenuItem = NSMenuItem(title: "立即收缩到右边缘", action: #selector(togglePeekAction), keyEquivalent: "h")
        peekMenuItem.target = self
        menu.addItem(peekMenuItem)

        menu.addItem(.separator())

        autoHideModeItem = NSMenuItem(title: "模式：自动吸边隐藏（推荐，不挡窗口）", action: #selector(setAutoHideModeAction), keyEquivalent: "")
        autoHideModeItem.target = self
        menu.addItem(autoHideModeItem)

        desktopModeItem = NSMenuItem(title: "模式：贴近桌面底层（Rainmeter 原版）", action: #selector(setDesktopModeAction), keyEquivalent: "")
        desktopModeItem.target = self
        menu.addItem(desktopModeItem)

        normalModeItem = NSMenuItem(title: "模式：普通窗口", action: #selector(setNormalModeAction), keyEquivalent: "")
        normalModeItem.target = self
        menu.addItem(normalModeItem)

        floatingModeItem = NSMenuItem(title: "模式：置顶悬浮", action: #selector(setFloatingModeAction), keyEquivalent: "")
        floatingModeItem.target = self
        menu.addItem(floatingModeItem)

        menu.addItem(.separator())

        regularGlassItem = NSMenuItem(title: "液态玻璃：标准质感（推荐）", action: #selector(setRegularGlassAction), keyEquivalent: "")
        regularGlassItem.target = self
        menu.addItem(regularGlassItem)

        clearGlassItem = NSMenuItem(title: "液态玻璃：极致通透", action: #selector(setClearGlassAction), keyEquivalent: "")
        clearGlassItem.target = self
        menu.addItem(clearGlassItem)

        hoverMenuItem = NSMenuItem(title: "鼠标悬停自动展开月历", action: #selector(toggleHoverExpandAction), keyEquivalent: "")
        hoverMenuItem.target = self
        menu.addItem(hoverMenuItem)

        menu.addItem(.separator())

        let syncItem = NSMenuItem(title: "立即同步", action: #selector(syncNowAction), keyEquivalent: "r")
        syncItem.target = self
        menu.addItem(syncItem)

        let settingsItem = NSMenuItem(title: "设置与云端连接…", action: #selector(openSettingsAction), keyEquivalent: ",")
        settingsItem.target = self
        menu.addItem(settingsItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(title: "退出 CalendarBoard", action: #selector(quitAction), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem.menu = menu
        refreshMenuStates()
    }

    private func setupWindow() {
        let initialFrame = targetFrame(state: .sidebar)
        window = WidgetWindow(
            contentRect: initialFrame,
            styleMask: [.borderless, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = true
        window.isMovableByWindowBackground = false
        window.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary]
        window.appearance = NSAppearance(named: .darkAqua)
        applyDockModeLevel()

        containerView = HoverContainerView(frame: NSRect(origin: .zero, size: initialFrame.size))
        containerView.autoresizingMask = [.width, .height]
        containerView.wantsLayer = true
        containerView.layer?.backgroundColor = NSColor.clear.cgColor

        let config = WKWebViewConfiguration()
        config.userContentController.add(self, name: "native")
        config.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")

        webView = WKWebView(frame: containerView.bounds, configuration: config)
        webView.autoresizingMask = [.width, .height]
        webView.navigationDelegate = self
        webView.setValue(false, forKey: "drawsBackground")

        if #available(macOS 26.0, *) {
            let glass = NSGlassEffectView(frame: containerView.bounds)
            glass.autoresizingMask = [.width, .height]
            glass.cornerRadius = 24
            glass.contentView = webView
            if #available(macOS 27.0, *) {
                glass.effectIsInteractive = true
            }
            containerView.addSubview(glass)
            glassBackingView = glass
            applyGlassStyle()
        } else {
            containerView.layer?.cornerRadius = 22
            containerView.layer?.masksToBounds = true
            let visual = NSVisualEffectView(frame: containerView.bounds)
            visual.autoresizingMask = [.width, .height]
            visual.material = .hudWindow
            visual.blendingMode = .behindWindow
            visual.state = .active
            containerView.addSubview(visual)
            containerView.addSubview(webView)
            glassBackingView = visual
        }

        containerView.onMouseEntered = { [weak self] in
            self?.handleHoverEntered()
        }
        containerView.onMouseExited = { [weak self] in
            self?.handleHoverExited()
        }

        window.contentView = containerView
    }

    private func applyGlassStyle() {
        if #available(macOS 26.0, *), let glass = glassBackingView as? NSGlassEffectView {
            switch glassStyle {
            case .regular:
                glass.style = .regular
                glass.tintColor = NSColor(calibratedRed: 0.07, green: 0.11, blue: 0.17, alpha: 0.22)
            case .clear:
                glass.style = .clear
                glass.tintColor = NSColor(calibratedRed: 0.08, green: 0.12, blue: 0.18, alpha: 0.10)
            }
            glass.cornerRadius = panelState == .peek ? 9 : 24
        }
        defaults.set(glassStyle.rawValue, forKey: "cb_glass_style")
        refreshMenuStates()
        notifyWebWindowState()
    }

    private func loadUI() {
        if let resourceURL = Bundle.main.resourceURL?.appendingPathComponent("ui/widget.html"),
           FileManager.default.fileExists(atPath: resourceURL.path) {
            webView.loadFileURL(resourceURL, allowingReadAccessTo: resourceURL.deletingLastPathComponent())
        } else {
            let cwd = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
            let fallback = cwd.appendingPathComponent("ui/widget.html")
            webView.loadFileURL(fallback, allowingReadAccessTo: fallback.deletingLastPathComponent())
        }
    }

    private func activeScreen() -> NSScreen {
        window?.screen ?? NSScreen.main ?? NSScreen.screens.first!
    }

    private func targetFrame(state: PanelState) -> NSRect {
        let visible = activeScreen().visibleFrame
        switch state {
        case .peek:
            let x = visible.maxX - peekWidth - 4
            let y = visible.midY - peekHeight / 2
            return NSRect(x: x, y: y, width: peekWidth, height: peekHeight)
        case .sidebar:
            let h = max(560, visible.height - outerMargin * 2)
            let w = min(sidebarWidth, visible.width - outerMargin * 2)
            let x = visible.maxX - w - outerMargin
            let y = visible.minY + outerMargin
            return NSRect(x: x, y: y, width: w, height: h)
        case .expanded:
            let h = max(560, visible.height - outerMargin * 2)
            let w = min(maxExpandedWidth, visible.width - outerMargin * 2)
            let x = visible.maxX - w - outerMargin
            let y = visible.minY + outerMargin
            return NSRect(x: x, y: y, width: w, height: h)
        }
    }

    private func positionWindow(state: PanelState, animated: Bool) {
        panelState = state
        let frame = targetFrame(state: state)

        if #available(macOS 26.0, *), let glass = glassBackingView as? NSGlassEffectView {
            glass.cornerRadius = state == .peek ? 9 : 24
        }

        refreshMenuStates()
        notifyWebWindowState()

        if animated {
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = state == .peek ? 0.24 : 0.28
                ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
                window.animator().setFrame(frame, display: true)
            }
        } else {
            window.setFrame(frame, display: true)
        }
    }

    private func applyDockModeLevel() {
        switch dockMode {
        case .desktop:
            window.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.desktopIconWindow)) + 1)
        case .normal:
            window.level = .normal
        case .autoHide, .floating:
            window.level = .floating
        }
        defaults.set(dockMode.rawValue, forKey: "cb_dock_mode")
        refreshMenuStates()
        notifyWebWindowState()
    }

    private func refreshMenuStates() {
        expandMenuItem?.title = panelState == .expanded ? "收起月历（仅显示侧栏）" : "展开完整月历"
        peekMenuItem?.title = panelState == .peek ? "滑出日历侧栏" : "立即收缩到右边缘"
        hoverMenuItem?.state = hoverExpandEnabled ? .on : .off

        autoHideModeItem?.state = dockMode == .autoHide ? .on : .off
        desktopModeItem?.state = dockMode == .desktop ? .on : .off
        normalModeItem?.state = dockMode == .normal ? .on : .off
        floatingModeItem?.state = dockMode == .floating ? .on : .off

        regularGlassItem?.state = glassStyle == .regular ? .on : .off
        clearGlassItem?.state = glassStyle == .clear ? .on : .off
    }

    private func handleHoverEntered() {
        initialCollapseTimer?.invalidate()
        hoverTimer?.invalidate()

        if panelState == .peek {
            hoverTimer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: false) { [weak self] _ in
                guard let self = self, self.panelState == .peek else { return }
                let target: PanelState = self.hoverExpandEnabled ? .expanded : .sidebar
                self.positionWindow(state: target, animated: true)
            }
            return
        }

        if hoverExpandEnabled && panelState == .sidebar {
            hoverTimer = Timer.scheduledTimer(withTimeInterval: 0.16, repeats: false) { [weak self] _ in
                guard let self = self, self.hoverExpandEnabled, self.panelState == .sidebar else { return }
                self.positionWindow(state: .expanded, animated: true)
            }
        }
    }

    private func handleHoverExited() {
        hoverTimer?.invalidate()
        guard !isModalOpen, !isPinnedOpen else { return }

        hoverTimer = Timer.scheduledTimer(withTimeInterval: 0.34, repeats: false) { [weak self] _ in
            guard let self = self, !self.isModalOpen, !self.isPinnedOpen else { return }
            let mouse = NSEvent.mouseLocation
            if self.window.frame.insetBy(dx: -10, dy: -10).contains(mouse) { return }

            if self.dockMode == .autoHide {
                if self.panelState != .peek {
                    self.positionWindow(state: .peek, animated: true)
                }
            } else if self.hoverExpandEnabled && self.panelState == .expanded {
                self.positionWindow(state: .sidebar, animated: true)
            }
        }
    }

    private func notifyWebWindowState() {
        guard webView != nil else { return }
        let payload: [String: Any] = [
            "panelState": panelState.rawValue,
            "expanded": panelState == .expanded,
            "peeking": panelState == .peek,
            "pinned": isPinnedOpen,
            "hoverExpand": hoverExpandEnabled,
            "dockMode": dockMode.rawValue,
            "glassStyle": glassStyle.rawValue,
            "liquidGlassSupported": {
                if #available(macOS 26.0, *) { return true }
                return false
            }()
        ]
        if let data = try? JSONSerialization.data(withJSONObject: payload),
           let json = String(data: data, encoding: .utf8) {
            webView.evaluateJavaScript("window.__onNativeState && window.__onNativeState(\(json));", completionHandler: nil)
        }
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        var snapshot: [String: String] = [:]
        for key in ["config", "session", "cache", "demoItems", "mode"] {
            if let val = defaults.string(forKey: storePrefix + key) {
                snapshot[key] = val
            }
        }
        let initPayload: [String: Any] = [
            "store": snapshot,
            "state": [
                "panelState": panelState.rawValue,
                "expanded": panelState == .expanded,
                "peeking": panelState == .peek,
                "pinned": isPinnedOpen,
                "hoverExpand": hoverExpandEnabled,
                "dockMode": dockMode.rawValue,
                "glassStyle": glassStyle.rawValue
            ]
        ]
        if let data = try? JSONSerialization.data(withJSONObject: initPayload),
           let json = String(data: data, encoding: .utf8) {
            webView.evaluateJavaScript("window.__initNative && window.__initNative(\(json));", completionHandler: nil)
        }
    }

    // MARK: - WKScriptMessageHandler

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let action = body["action"] as? String else { return }

        switch action {
        case "setPanelState":
            if let raw = body["state"] as? String, let target = PanelState(rawValue: raw) {
                hoverTimer?.invalidate()
                initialCollapseTimer?.invalidate()
                positionWindow(state: target, animated: true)
            }
        case "setPinned":
            if let pinned = body["pinned"] as? Bool {
                isPinnedOpen = pinned
                notifyWebWindowState()
            }
        case "setModalOpen":
            if let open = body["open"] as? Bool {
                isModalOpen = open
                if open {
                    hoverTimer?.invalidate()
                    initialCollapseTimer?.invalidate()
                    if panelState == .peek {
                        positionWindow(state: .sidebar, animated: true)
                    }
                    NSApp.activate(ignoringOtherApps: true)
                    window.makeKeyAndOrderFront(nil)
                }
            }
        case "setHoverExpand":
            if let enabled = body["enabled"] as? Bool {
                hoverExpandEnabled = enabled
                defaults.set(enabled, forKey: "cb_hover_expand")
                refreshMenuStates()
                notifyWebWindowState()
            }
        case "setDockMode":
            if let raw = body["mode"] as? String, let mode = DockDisplayMode(rawValue: raw) {
                switchDockMode(mode)
            }
        case "setGlassStyle":
            if let raw = body["style"] as? String, let style = GlassAppearanceStyle(rawValue: raw) {
                glassStyle = style
                applyGlassStyle()
            }
        case "saveStore":
            if let key = body["key"] as? String {
                if let value = body["value"] as? String {
                    defaults.set(value, forKey: storePrefix + key)
                } else {
                    defaults.removeObject(forKey: storePrefix + key)
                }
            }
        case "openExternal":
            if let raw = body["url"] as? String, let url = URL(string: raw) {
                NSWorkspace.shared.open(url)
            }
        case "httpRequest":
            handleHttpRequest(body)
        default:
            break
        }
    }

    private func switchDockMode(_ mode: DockDisplayMode) {
        dockMode = mode
        applyDockModeLevel()
        if mode == .desktop {
            isPinnedOpen = false
            if panelState == .peek {
                positionWindow(state: .sidebar, animated: true)
            }
        } else if mode == .autoHide {
            window.makeKeyAndOrderFront(nil)
        } else {
            if panelState == .peek {
                positionWindow(state: .sidebar, animated: true)
            }
            window.makeKeyAndOrderFront(nil)
        }
    }

    private func handleHttpRequest(_ payload: [String: Any]) {
        guard let requestId = payload["id"] as? String,
              let urlStr = payload["url"] as? String,
              let url = URL(string: urlStr) else { return }

        var req = URLRequest(url: url)
        req.httpMethod = (payload["method"] as? String) ?? "GET"
        req.timeoutInterval = 12

        if let headers = payload["headers"] as? [String: String] {
            for (k, v) in headers {
                req.setValue(v, forHTTPHeaderField: k)
            }
        }
        if let bodyStr = payload["body"] as? String {
            req.httpBody = bodyStr.data(using: .utf8)
        }

        let task = URLSession.shared.dataTask(with: req) { [weak self] data, response, error in
            var result: [String: Any] = ["id": requestId]
            if let error = error {
                result["networkError"] = error.localizedDescription
            } else {
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                result["status"] = status
                result["text"] = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            }
            DispatchQueue.main.async {
                guard let self = self else { return }
                if let jsonData = try? JSONSerialization.data(withJSONObject: result),
                   let json = String(data: jsonData, encoding: .utf8) {
                    self.webView.evaluateJavaScript("window.__nativeHttpCallback && window.__nativeHttpCallback(\(json));", completionHandler: nil)
                }
            }
        }
        task.resume()
    }

    // MARK: - Menu Actions

    @objc private func toggleExpandAction() {
        hoverTimer?.invalidate()
        initialCollapseTimer?.invalidate()
        let next: PanelState = panelState == .expanded ? .sidebar : .expanded
        positionWindow(state: next, animated: true)
        if dockMode != .desktop {
            window.makeKeyAndOrderFront(nil)
        }
    }

    @objc private func togglePeekAction() {
        hoverTimer?.invalidate()
        initialCollapseTimer?.invalidate()
        isPinnedOpen = false
        let next: PanelState = panelState == .peek ? .sidebar : .peek
        positionWindow(state: next, animated: true)
        if next != .peek && dockMode != .desktop {
            window.makeKeyAndOrderFront(nil)
        }
    }

    @objc private func setAutoHideModeAction() { switchDockMode(.autoHide) }
    @objc private func setDesktopModeAction() { switchDockMode(.desktop) }
    @objc private func setNormalModeAction() { switchDockMode(.normal) }
    @objc private func setFloatingModeAction() { switchDockMode(.floating) }

    @objc private func setRegularGlassAction() {
        glassStyle = .regular
        applyGlassStyle()
    }

    @objc private func setClearGlassAction() {
        glassStyle = .clear
        applyGlassStyle()
    }

    @objc private func toggleHoverExpandAction() {
        hoverExpandEnabled.toggle()
        defaults.set(hoverExpandEnabled, forKey: "cb_hover_expand")
        refreshMenuStates()
        notifyWebWindowState()
    }

    @objc private func syncNowAction() {
        webView.evaluateJavaScript("window.__triggerSync && window.__triggerSync();", completionHandler: nil)
    }

    @objc private func openSettingsAction() {
        hoverTimer?.invalidate()
        initialCollapseTimer?.invalidate()
        if panelState == .peek {
            positionWindow(state: .sidebar, animated: true)
        }
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        webView.evaluateJavaScript("window.__openSettings && window.__openSettings();", completionHandler: nil)
    }

    @objc private func handleScreenChange() {
        positionWindow(state: panelState, animated: false)
    }

    @objc private func quitAction() {
        NSApp.terminate(nil)
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
