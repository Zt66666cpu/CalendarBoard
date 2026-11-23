import AppKit
import Combine
import SwiftUI
import DynamicNotchKit

enum IslandSurface: String {
    case today
    case month
    case quickAdd
    case review

    var preferredSize: CGSize {
        switch self {
        case .today:
            return CGSize(width: 380, height: 520)
        case .month:
            return CGSize(width: 860, height: 560)
        case .quickAdd:
            return CGSize(width: 380, height: 220)
        case .review:
            return CGSize(width: 380, height: 480)
        }
    }
}

enum IslandPresentationMode: Equatable {
    case hidden
    case compact
    case prompt(text: String, targetSurface: String)
    case expanded
}

@MainActor
final class IslandController: ObservableObject, WebBridgeDelegate {
    @Published private(set) var mode: IslandPresentationMode = .hidden
    @Published private(set) var surface: IslandSurface = .today
    @Published private(set) var isFloatingScreen: Bool = false

    let timeline: IslandTimeline
    let webBridge: WebBridge

    private var notch: DynamicNotch<ExpandedIslandView, CompactLeadingView, CompactTrailingView>!
    private var cancellables = Set<AnyCancellable>()
    var onStateChanged: (() -> Void)?

    private var isModalOpen = false
    private var isInputFocused = false
    private var pinnedByHotkey = false
    private var expandTimer: Timer?
    private var collapseTimer: Timer?
    private var promptTimer: Timer?
    private var localKeyMonitor: Any?
    private var globalKeyMonitor: Any?

    init(timeline: IslandTimeline, webBridge: WebBridge) {
        self.timeline = timeline
        self.webBridge = webBridge
    }

    func start() {
        createNotchIfNeeded()
        applyTransitionConfiguration()
        installKeyMonitors()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(screenParametersDidChange),
            name: NSApplication.didChangeScreenParametersNotification,
            object: nil
        )

        updateScreenState()
        refreshCompactOrHidden()
    }

    // MARK: - WebBridgeDelegate

    var currentSurfaceRawValue: String {
        surface.rawValue
    }

    var currentSurfaceStyleRawValue: String {
        isFloatingScreen ? "floating" : "notch"
    }

    func webBridgeDidUpdateModalOpen(_ open: Bool) {
        isModalOpen = open
        guard open else { return }

        collapseTimer?.invalidate()
        collapseTimer = nil

        if mode != .expanded {
            expand(surface: surface, activateKeyboard: false, pin: pinnedByHotkey)
        }
        focusPanel()
    }

    func webBridgeDidUpdateInputFocus(_ focused: Bool) {
        isInputFocused = focused
        guard focused else { return }

        collapseTimer?.invalidate()
        collapseTimer = nil
        focusPanel()
    }

    func webBridgeDidRequestSurface(_ surfaceName: String) {
        guard let requested = IslandSurface(rawValue: surfaceName) else { return }
        surface = requested
        if mode == .expanded {
            configurePanelWindow(forExpanded: true)
        }
        onStateChanged?()
    }

    func webBridgeDidRequestCollapse() {
        collapse()
    }

    // MARK: - Screen state

    static func screenHasNotch(_ screen: NSScreen) -> Bool {
        screen.auxiliaryTopLeftArea?.width != nil && screen.auxiliaryTopRightArea?.width != nil
    }

    private func targetScreen() -> NSScreen {
        if let screen = NSScreen.main {
            return screen
        }
        if let screen = NSScreen.screens.first {
            return screen
        }
        fatalError("No screen available")
    }

    private func updateScreenState() {
        let floating = !Self.screenHasNotch(targetScreen())
        isFloatingScreen = floating
        webBridge.setSurfaceStyle(floating ? "floating" : "notch")

        if mode == .compact || mode == .hidden {
            refreshCompactOrHidden()
        }
    }

    @objc private func screenParametersDidChange() {
        Task { @MainActor [weak self] in
            self?.updateScreenState()
        }
    }

    // MARK: - Appearance and transition configuration

    private func applyTransitionConfiguration() {
        let reduceMotion = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion

        if reduceMotion {
            let eased = Animation.easeInOut(duration: 0.20)
            notch.transitionConfiguration = DynamicNotchTransitionConfiguration(
                openingAnimation: eased,
                closingAnimation: eased,
                conversionAnimation: eased,
                skipIntermediateHides: true
            )
        } else {
            notch.transitionConfiguration = DynamicNotchTransitionConfiguration(
                openingAnimation: .spring(response: 0.42, dampingFraction: 0.80),
                closingAnimation: .easeOut(duration: 0.30),
                conversionAnimation: .spring(response: 0.42, dampingFraction: 0.80),
                skipIntermediateHides: true
            )
        }
    }

    private func applyWindowAppearance() {
        notch.windowController?.window?.appearance = NSAppearance(named: .darkAqua)
    }

    private func configurePanelWindow(forExpanded expanded: Bool) {
        guard let panel = notch.windowController?.window else { return }
        panel.appearance = NSAppearance(named: .darkAqua)
        guard expanded else { return }

        let screen = targetScreen()
        let minimumWidth = max(screen.frame.width / 2, min(screen.visibleFrame.width, 920))
        let minimumHeight = max(screen.frame.height / 2, min(screen.visibleFrame.height, 640))

        var frame = panel.frame
        let width = max(frame.width, minimumWidth)
        let height = max(frame.height, minimumHeight)
        frame.size = NSSize(width: width, height: height)
        frame.origin = NSPoint(
            x: screen.frame.midX - width / 2,
            y: screen.frame.maxY - height
        )
        panel.setFrame(frame, display: true)
    }

    private func focusPanel() {
        guard let panel = notch.windowController?.window else { return }
        NSApp.activate(ignoringOtherApps: true)
        panel.makeKey()
    }

    // MARK: - Notch assembly

    private func createNotchIfNeeded() {
        guard notch == nil else { return }

        let notch = DynamicNotch(
            hoverBehavior: [],
            style: .auto,
            expanded: { ExpandedIslandView(controller: self) },
            compactLeading: { CompactLeadingView(timeline: self.timeline) },
            compactTrailing: { CompactTrailingView(timeline: self.timeline) }
        )
        self.notch = notch

        notch.$isHovering
            .removeDuplicates()
            .receive(on: RunLoop.main)
            .sink { [weak self] hovering in
                self?.handleHoverChange(hovering)
            }
            .store(in: &cancellables)

        applyWindowAppearance()
    }

    // MARK: - Hover behaviour

    private func handleHoverChange(_ hovering: Bool) {
        if hovering {
            collapseTimer?.invalidate()
            collapseTimer = nil

            if mode == .compact || isPromptMode {
                expandTimer?.invalidate()
                expandTimer = Timer(timeInterval: 0.20, repeats: false) { [weak self] _ in
                    Task { @MainActor in
                        guard let self else { return }
                        self.expandTimer = nil
                        guard self.mode == .compact || self.isPromptMode else { return }
                        guard self.notch.isHovering else { return }
                        self.expand(surface: .today, activateKeyboard: false, pin: false)
                    }
                }
                RunLoop.main.add(expandTimer!, forMode: .common)
            }
        } else {
            expandTimer?.invalidate()
            expandTimer = nil

            guard mode == .expanded,
                  !isModalOpen,
                  !isInputFocused,
                  !pinnedByHotkey else {
                return
            }

            collapseTimer?.invalidate()
            collapseTimer = Timer(timeInterval: 0.30, repeats: false) { [weak self] _ in
                Task { @MainActor in
                    guard let self else { return }
                    self.collapseTimer = nil
                    guard self.mode == .expanded,
                          !self.isModalOpen,
                          !self.isInputFocused,
                          !self.pinnedByHotkey,
                          !self.notch.isHovering else {
                        return
                    }
                    self.collapse()
                }
            }
            RunLoop.main.add(collapseTimer!, forMode: .common)
        }
    }

    // MARK: - Compact / hidden state

    func refreshCompactOrHidden() {
        guard mode != .expanded, !isPromptMode else { return }
        applyCompactOrHidden()
    }

    private func applyCompactOrHidden() {
        expandTimer?.invalidate()
        expandTimer = nil

        webBridge.attachToKeepAliveWindow()

        if timeline.compactState == .hidden {
            mode = .hidden
            Task { @MainActor [weak self] in
                guard let self else { return }
                await self.notch.hide()
                self.applyWindowAppearance()
            }
        } else {
            mode = .compact
            let screen = targetScreen()
            Task { @MainActor [weak self] in
                guard let self else { return }
                if self.isFloatingScreen {
                    await self.notch.expand(on: screen)
                } else {
                    await self.notch.compact(on: screen)
                }
                self.applyWindowAppearance()
            }
        }

        onStateChanged?()
    }

    // MARK: - Expansion

    func expand(surface: IslandSurface, activateKeyboard: Bool, pin: Bool) {
        expandTimer?.invalidate()
        expandTimer = nil
        collapseTimer?.invalidate()
        collapseTimer = nil
        promptTimer?.invalidate()
        promptTimer = nil

        self.surface = surface
        pinnedByHotkey = pin
        mode = .expanded
        webBridge.showSurface(surface.rawValue)

        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.notch.expand(on: self.targetScreen())
            self.applyWindowAppearance()
            self.configurePanelWindow(forExpanded: true)
            if activateKeyboard {
                self.focusPanel()
            }
        }

        onStateChanged?()
    }

    func collapse() {
        expandTimer?.invalidate()
        expandTimer = nil
        collapseTimer?.invalidate()
        collapseTimer = nil
        promptTimer?.invalidate()
        promptTimer = nil

        pinnedByHotkey = false
        isInputFocused = false
        isModalOpen = false

        applyCompactOrHidden()
    }

    func toggleFromHotkeyOrMenu() {
        if mode == .expanded {
            collapse()
        } else {
            expand(surface: .today, activateKeyboard: false, pin: true)
        }
    }

    func openQuickAdd() {
        expand(surface: .quickAdd, activateKeyboard: true, pin: true)
    }

    func openSettings() {
        expand(surface: .today, activateKeyboard: true, pin: true)
        webBridge.openSettings()
    }

    // MARK: - Prompt

    func showPrompt(text: String, targetSurface: String) {
        guard mode != .expanded else { return }
        let screen = targetScreen()
        guard !isFrontmostAppFullscreen(on: screen) else { return }

        promptTimer?.invalidate()
        promptTimer = nil
        collapseTimer?.invalidate()
        collapseTimer = nil
        expandTimer?.invalidate()
        expandTimer = nil

        mode = .prompt(text: text, targetSurface: targetSurface)

        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.notch.expand(on: screen)
            self.applyWindowAppearance()
        }

        promptTimer = Timer(timeInterval: 4, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.promptTimer = nil
                guard self.isPromptMode else { return }
                self.mode = .hidden
                self.refreshCompactOrHidden()
            }
        }
        RunLoop.main.add(promptTimer!, forMode: .common)
    }

    private var isPromptMode: Bool {
        if case .prompt = mode {
            return true
        }
        return false
    }

    private func isFrontmostAppFullscreen(on screen: NSScreen) -> Bool {
        screen.frame.height - screen.visibleFrame.height <= 1
    }

    // MARK: - Keyboard

    private func installKeyMonitors() {
        guard localKeyMonitor == nil, globalKeyMonitor == nil else { return }

        localKeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, event.keyCode == 53 else { return event }
            guard self.mode == .expanded else { return event }
            self.handleEscapeKey()
            return nil
        }

        globalKeyMonitor = NSEvent.addGlobalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard event.keyCode == 53 else { return }
            Task { @MainActor in
                guard let self, self.mode == .expanded else { return }
                self.handleEscapeKey()
            }
        }
    }

    private func handleEscapeKey() {
        guard mode == .expanded, !isModalOpen else { return }

        if surface != .today {
            if surface == .month {
                surface = .today
                webBridge.showSurface("today")
                configurePanelWindow(forExpanded: true)
                onStateChanged?()
            } else {
                collapse()
            }
        } else {
            collapse()
        }
    }
}

struct ExpandedIslandView: View {
    @ObservedObject var controller: IslandController

    var body: some View {
        switch controller.mode {
        case .compact:
            HStack(spacing: 8) {
                CompactLeadingView(timeline: controller.timeline)
                CompactTrailingView(timeline: controller.timeline)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .contentShape(Rectangle())
            .onTapGesture {
                controller.expand(surface: .today, activateKeyboard: false, pin: false)
            }

        case .prompt(let text, let targetSurface):
            HStack(spacing: 8) {
                Image(systemName: "bell.badge.fill")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(islandTodoColor)
                Text(text)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
            .onTapGesture {
                let target = IslandSurface(rawValue: targetSurface) ?? .today
                controller.expand(surface: target, activateKeyboard: false, pin: true)
            }

        case .expanded:
            let size = clampedSize(for: controller.surface)
            WebViewHostRepresentable(bridge: controller.webBridge)
                .frame(width: size.width, height: size.height)

        case .hidden:
            EmptyView()
        }
    }

    private func clampedSize(for surface: IslandSurface) -> CGSize {
        let preferred = surface.preferredSize
        guard let visibleFrame = NSScreen.main?.visibleFrame else {
            return preferred
        }
        return CGSize(
            width: min(preferred.width, visibleFrame.width),
            height: min(preferred.height, visibleFrame.height)
        )
    }
}
