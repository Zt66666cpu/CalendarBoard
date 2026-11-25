import AppKit
import Combine
import SwiftUI
import UniformTypeIdentifiers
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
    case dropTarget
}

@MainActor
final class IslandController: ObservableObject, WebBridgeDelegate {
    @Published private(set) var mode: IslandPresentationMode = .hidden
    /// 画面上实际渲染的内容。收起时要等库的收起动画结束后才跟随 mode 变化。
    @Published private(set) var contentMode: IslandPresentationMode = .hidden
    /// 每次展开或收起都 +1；收起的收尾逻辑只在编号未变时执行。
    private var transitionID = 0
    @Published private(set) var surface: IslandSurface = .today
    @Published private(set) var isFloatingScreen: Bool = false
    /// 紧凑胶囊当前生效的左右布局方案（受菜单栏可用空间与鼠标避让控制）。
    @Published private(set) var compactLayout: CompactLayout = .defaultLayout

    let timeline: IslandTimeline
    let webBridge: WebBridge

    private var notch: DynamicNotch<ExpandedIslandView, CompactLeadingView, CompactTrailingView>!
    private var cancellables = Set<AnyCancellable>()
    var onStateChanged: (() -> Void)?

    private let spaceProbe = MenuBarSpaceProbe()
    let notchSpacer = NotchSpacerItem()     // internal，StatusMenu 要用
    private var spacerProbeTimer: Timer?
    private var lastMeasuredSpace: MenuBarSpace = .unconstrained
    private var hasMeasuredSpace = false
    private var committedCompactTier: CompactLayout.Tier = .splitFull
    private var pendingCompactTier: CompactLayout.Tier?
    private var spaceProbeGeneration = 0
    private var spaceProbeTimer: Timer?
    private var layoutDebounceTimer: Timer?
    private var activationFollowUpTimer: Timer?
    private var mouseDodgedSide: MouseDodgeSide = .none
    private var dodgeRestoreTimer: Timer?

    private var isModalOpen = false
    private var isInputFocused = false
    private var pinnedByHotkey = false
    private var expandTimer: Timer?
    private var collapseTimer: Timer?
    private var promptTimer: Timer?
    private var localKeyMonitor: Any?
    private var globalKeyMonitor: Any?
    private var localMouseMonitor: Any?
    private var globalMouseMonitor: Any?
    private var menuBarClickMonitor: Any?
    private var clickProbeTimers: [Timer] = []

    init(timeline: IslandTimeline, webBridge: WebBridge) {
        self.timeline = timeline
        self.webBridge = webBridge
    }

    func start() {
        createNotchIfNeeded()
        applyTransitionConfiguration()
        installKeyMonitors()
        installMouseMonitors()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(screenParametersDidChange),
            name: NSApplication.didChangeScreenParametersNotification,
            object: nil
        )

        let workspaceCenter = NSWorkspace.shared.notificationCenter
        workspaceCenter.addObserver(
            self,
            selector: #selector(workspaceApplicationsDidChange(_:)),
            name: NSWorkspace.didActivateApplicationNotification,
            object: nil
        )
        workspaceCenter.addObserver(
            self,
            selector: #selector(workspaceApplicationsDidChange(_:)),
            name: NSWorkspace.didLaunchApplicationNotification,
            object: nil
        )
        workspaceCenter.addObserver(
            self,
            selector: #selector(workspaceApplicationsDidChange(_:)),
            name: NSWorkspace.didTerminateApplicationNotification,
            object: nil
        )

        notchSpacer.onClick = { [weak self] in
            self?.expand(surface: .today, activateKeyboard: false, pin: false)
        }
        notchSpacer.onLengthApplied = { [weak self] in
            self?.scheduleProbeAfterSpacerChange()
        }
        notchSpacer.installIfEnabled()

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
        withAnimation(surfaceAnimation) {
            surface = requested
        }
        applyWindowAppearance()
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

        if floating {
            stopSpaceProbingAndResetLayout()
        }

        if mode == .compact || mode == .hidden {
            refreshCompactOrHidden()
        }
        updateNotchSpacer()
    }

    @objc private func screenParametersDidChange() {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.updateScreenState()
            if self.mode == .compact, !self.isFloatingScreen {
                self.probeMenuBarSpace(immediate: false)
            }
        }
    }

    @objc private func workspaceApplicationsDidChange(_ notification: Notification) {
        let isActivation = notification.name == NSWorkspace.didActivateApplicationNotification
        Task { @MainActor [weak self] in
            guard let self, self.mode == .compact, !self.isFloatingScreen else { return }
            self.probeMenuBarSpace(immediate: false)

            // 切换前台应用时，新应用的菜单栏偶发滞后几十毫秒才完成更新，补测一次确保准确
            if isActivation {
                self.activationFollowUpTimer?.invalidate()
                self.activationFollowUpTimer = Timer(timeInterval: 0.15, repeats: false) { [weak self] _ in
                    Task { @MainActor in
                        guard let self, self.mode == .compact, !self.isFloatingScreen else { return }
                        self.activationFollowUpTimer = nil
                        self.probeMenuBarSpace(immediate: false)
                    }
                }
                RunLoop.main.add(self.activationFollowUpTimer!, forMode: .common)
            }
        }
    }

    // MARK: - Appearance and transition configuration

    /// 面板窗口最终尺寸：容纳所有 surface 中最大的展开内容，外加库内边距和阴影余量。
    /// 计算依据（DynamicNotchKit NotchView）：
    ///   宽 = 内容宽 + safeAreaInset 15×2 + topCornerRadius 15×2（style 为 .auto 时展开圆角 top=15）
    ///   高 = 刘海高 + 内容高 + 底部 safeAreaInset 15
    /// 另加阴影余量（NotchContentView shadow radius 10~20）。
    /// 无刘海屏走 NotchlessView，其外层还有 .padding(20)（上下各 20），需再额外留 40。
    static func panelSize(for screen: NSScreen) -> CGSize {
        let surfaces: [IslandSurface] = [.today, .month, .quickAdd, .review]
        let maxW = surfaces.map(\.preferredSize.width).max() ?? 860
        let maxH = surfaces.map(\.preferredSize.height).max() ?? 560
        let contentW = min(maxW, screen.visibleFrame.width)
        let contentH = min(maxH, screen.visibleFrame.height)
        let notchH = max(screen.safeAreaInsets.top, screen.frame.maxY - screen.visibleFrame.maxY)
        let shadowMargin: CGFloat = 24
        let floatingPadding: CGFloat = screenHasNotch(screen) ? 0 : 40
        return CGSize(
            width: contentW + 15 * 2 + 15 * 2 + shadowMargin * 2,
            height: notchH + contentH + 15 + shadowMargin + floatingPadding
        )
    }

    /// surface 切换（今日↔月历）的过渡动画；开启“减弱动态效果”时退化为短促淡变。
    private var surfaceAnimation: Animation {
        NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            ? .easeInOut(duration: 0.20)
            : .spring(response: 0.42, dampingFraction: 0.80)
    }

    /// 自己发起的收起动画（仅用于无刘海屏收回胶囊；刘海屏由库的 closingAnimation 负责）。
    private var collapseAnimation: Animation {
        NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            ? .easeInOut(duration: 0.20)
            : .easeOut(duration: 0.30)
    }

    private var collapseAnimationDuration: Double {
        NSWorkspace.shared.accessibilityDisplayShouldReduceMotion ? 0.20 : 0.30
    }

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
            compactLeading: { CompactLeadingView(controller: self) },
            compactTrailing: { CompactTrailingView(controller: self) }
        )
        self.notch = notch
        notch.panelSize = { screen in IslandController.panelSize(for: screen) }

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

    private func installMouseMonitors() {
        guard localMouseMonitor == nil, globalMouseMonitor == nil else { return }

        let mask: NSEvent.EventTypeMask = [.mouseMoved, .leftMouseDragged, .otherMouseDragged]

        localMouseMonitor = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
            Task { @MainActor in
                self?.handleMouseLocation(NSEvent.mouseLocation)
            }
            return event
        }

        globalMouseMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] _ in
            Task { @MainActor in
                self?.handleMouseLocation(NSEvent.mouseLocation)
            }
        }

        menuBarClickMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseUp]) { [weak self] _ in
            Task { @MainActor in self?.handleMenuBarClick(NSEvent.mouseLocation) }
        }
    }

    /// 点击菜单栏（可能是 » 展开/收回）后 0.35s、1.0s 各补测一次，不等 4 秒定时器
    private func handleMenuBarClick(_ point: NSPoint) {
        guard mode == .compact, !isFloatingScreen else { return }
        let screen = screen(containing: point) ?? targetScreen()
        guard let g = MenuBarSpaceProbe.notchGeometry(for: screen),
              point.y >= screen.frame.maxY - g.menuBarHeight - 2 else { return }
        clickProbeTimers.forEach { $0.invalidate() }
        clickProbeTimers = [0.35, 1.0].map { delay in
            let t = Timer(timeInterval: delay, repeats: false) { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.mode == .compact, !self.isFloatingScreen else { return }
                    self.probeMenuBarSpace(immediate: false)
                }
            }
            RunLoop.main.add(t, forMode: .common)
            return t
        }
    }

    private func screen(containing point: NSPoint) -> NSScreen? {
        NSScreen.screens.first { $0.frame.contains(point) }
    }

    private func notchHoverRect(on screen: NSScreen) -> NSRect {
        if let leftArea = screen.auxiliaryTopLeftArea,
           let rightArea = screen.auxiliaryTopRightArea {
            let notchWidth = max(160, screen.frame.width - leftArea.width - rightArea.width)
            let notchHeight = max(screen.safeAreaInsets.top, screen.frame.maxY - screen.visibleFrame.maxY)
            let width = notchWidth + 40
            let height = notchHeight + 8
            return NSRect(
                x: screen.frame.midX - width / 2,
                y: screen.frame.maxY - height,
                width: width,
                height: height
            )
        }

        let menubarHeight = max(24, screen.frame.maxY - screen.visibleFrame.maxY)
        let width: CGFloat = 200
        let height = menubarHeight + 38
        return NSRect(
            x: screen.frame.midX - width / 2,
            y: screen.frame.maxY - height,
            width: width,
            height: height
        )
    }

    private func expandedHoverRect(on screen: NSScreen) -> NSRect {
        let preferred = surface.preferredSize
        let size = CGSize(
            width: min(preferred.width, screen.visibleFrame.width),
            height: min(preferred.height, screen.visibleFrame.height)
        )
        let notchHeight = max(screen.safeAreaInsets.top, screen.frame.maxY - screen.visibleFrame.maxY)
        let width = size.width + 48
        let height = notchHeight + size.height + 44
        return NSRect(
            x: screen.frame.midX - width / 2,
            y: screen.frame.maxY - height,
            width: width,
            height: height
        )
    }

    private func handleMouseLocation(_ point: NSPoint) {
        let screen = screen(containing: point) ?? targetScreen()

        if mode == .compact, !isFloatingScreen {
            // 鼠标位于菜单栏且靠近刘海某一侧时，那一侧临时让开，并阻止误触展开面板
            let dodgingSide = updateMouseDodge(for: point, on: screen)
            if dodgingSide != .none {
                expandTimer?.invalidate()
                expandTimer = nil
                return
            }
        }

        if mode == .hidden || mode == .compact || isPromptMode {
            guard notchHoverRect(on: screen).contains(point) else {
                expandTimer?.invalidate()
                expandTimer = nil
                return
            }

            collapseTimer?.invalidate()
            collapseTimer = nil

            guard expandTimer == nil else { return }
            // 提前让网页把今日面板渲染好，避免网页渲染和展开动画第一帧抢时间。
            webBridge.showSurface("today")
            expandTimer = Timer(timeInterval: 0.20, repeats: false) { [weak self] _ in
                Task { @MainActor in
                    guard let self else { return }
                    self.expandTimer = nil
                    guard self.mode == .hidden || self.mode == .compact || self.isPromptMode else { return }
                    let current = NSEvent.mouseLocation
                    let currentScreen = self.screen(containing: current) ?? self.targetScreen()
                    guard self.detectMouseDodgeSide(at: current, on: currentScreen) == .none else { return }
                    guard self.notchHoverRect(on: currentScreen).contains(current) else { return }
                    self.expand(surface: .today, activateKeyboard: false, pin: false)
                }
            }
            RunLoop.main.add(expandTimer!, forMode: .common)
            return
        }

        if mode == .expanded {
            if expandedHoverRect(on: screen).contains(point) || notch.isHovering {
                collapseTimer?.invalidate()
                collapseTimer = nil
                return
            }

            guard !isModalOpen, !isInputFocused, !pinnedByHotkey else { return }
            guard collapseTimer == nil else { return }
            collapseTimer = Timer(timeInterval: 0.30, repeats: false) { [weak self] _ in
                Task { @MainActor in
                    guard let self else { return }
                    self.collapseTimer = nil
                    guard self.mode == .expanded else { return }
                    let current = NSEvent.mouseLocation
                    let currentScreen = self.screen(containing: current) ?? self.targetScreen()
                    guard !self.expandedHoverRect(on: currentScreen).contains(current) else { return }
                    guard !self.isModalOpen, !self.isInputFocused, !self.pinnedByHotkey else { return }
                    self.collapse()
                }
            }
            RunLoop.main.add(collapseTimer!, forMode: .common)
        }
    }

    private func handleHoverChange(_ hovering: Bool) {
        _ = hovering
        handleMouseLocation(NSEvent.mouseLocation)
    }

    // MARK: - Menu bar space probing & mouse dodge

    /// 紧凑态下每 4 秒探测一次刘海两侧菜单栏空间，并在切应用/改屏幕参数时触发重测。
    private func startSpaceProbingIfNeeded() {
        guard mode == .compact, !isFloatingScreen else { return }
        if spaceProbeTimer == nil {
            let timer = Timer(timeInterval: 4.0, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.mode == .compact, !self.isFloatingScreen else { return }
                    self.probeMenuBarSpace(immediate: false)
                }
            }
            RunLoop.main.add(timer, forMode: .common)
            spaceProbeTimer = timer
        }
    }

    private func stopSpaceProbingAndResetLayout() {
        spaceProbeGeneration += 1
        spaceProbeTimer?.invalidate()
        spaceProbeTimer = nil
        layoutDebounceTimer?.invalidate()
        layoutDebounceTimer = nil
        activationFollowUpTimer?.invalidate()
        activationFollowUpTimer = nil
        dodgeRestoreTimer?.invalidate()
        dodgeRestoreTimer = nil
        spacerProbeTimer?.invalidate()
        spacerProbeTimer = nil
        clickProbeTimers.forEach { $0.invalidate() }
        clickProbeTimers = []
        pendingCompactTier = nil
        let wasDodging = (mouseDodgedSide != .none)
        mouseDodgedSide = .none

        if isFloatingScreen {
            committedCompactTier = .splitFull
            applyEffectiveCompactLayout(animated: false)
        } else if wasDodging {
            applyEffectiveCompactLayout(animated: false)
        }
    }

    private func probeMenuBarSpace(immediate: Bool) {
        guard mode == .compact, !isFloatingScreen else { return }
        spaceProbeGeneration += 1
        let generation = spaceProbeGeneration
        let screen = targetScreen()

        spaceProbe.probe(on: screen) { [weak self] space in
            guard let self,
                  generation == self.spaceProbeGeneration,
                  self.mode == .compact,
                  !self.isFloatingScreen else {
                return
            }

            let isFirstProbe = !self.hasMeasuredSpace
            self.hasMeasuredSpace = true
            let overflowChanged = space.overflowExpanded != self.lastMeasuredSpace.overflowExpanded
            self.lastMeasuredSpace = space

            if let geometry = MenuBarSpaceProbe.notchGeometry(for: screen) {
                self.notchSpacer.ensureLeftmost(foreignLeftmostMinX: space.leftmostForeignStatusMinX, geometry: geometry)
                #if DEBUG
                print("[NotchSpacer] effective=\(self.notchSpacer.isEffective(on: geometry)) len=\(self.notchSpacer.appliedLength) rightAvail=\(space.rightAvailable) foreignMinX=\(String(describing: space.leftmostForeignStatusMinX)) notchRight=\(geometry.notchRight) overflowExpanded=\(space.overflowExpanded)")
                #endif
            }

            let candidateTier = MenuBarSpaceProbe.selectTier(
                for: self.timeline.compactState,
                space: space,
                currentTier: self.committedCompactTier
            )

            if immediate || isFirstProbe {
                self.layoutDebounceTimer?.invalidate()
                self.layoutDebounceTimer = nil
                self.pendingCompactTier = nil
                self.committedCompactTier = candidateTier
                self.applyEffectiveCompactLayout(animated: !isFirstProbe)
            } else {
                self.scheduleTierTransitionIfNeeded(to: candidateTier)
            }

            if overflowChanged {
                self.applyEffectiveCompactLayout(animated: true, fast: true)
            }
        }
    }

    /// 宽度变化稳定 0.5 秒后再切换布局档位，避免临界状态抖动。
    private func scheduleTierTransitionIfNeeded(to candidateTier: CompactLayout.Tier) {
        if candidateTier == committedCompactTier {
            layoutDebounceTimer?.invalidate()
            layoutDebounceTimer = nil
            pendingCompactTier = nil
            return
        }

        guard pendingCompactTier != candidateTier else { return }
        layoutDebounceTimer?.invalidate()
        pendingCompactTier = candidateTier

        layoutDebounceTimer = Timer(timeInterval: 0.5, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.layoutDebounceTimer = nil
                guard self.mode == .compact,
                      !self.isFloatingScreen,
                      let targetTier = self.pendingCompactTier else {
                    self.pendingCompactTier = nil
                    return
                }
                self.pendingCompactTier = nil
                self.committedCompactTier = targetTier
                self.applyEffectiveCompactLayout(animated: true)
            }
        }
        RunLoop.main.add(layoutDebounceTimer!, forMode: .common)
    }

    /// compactState 内容变化（例如待办→上课）时，基于最近一次探测结果立即重算档位。
    private func recomputeCompactTierForStateChange() {
        guard !isFloatingScreen else {
            committedCompactTier = .splitFull
            applyEffectiveCompactLayout(animated: false)
            return
        }
        guard hasMeasuredSpace else { return }
        let candidateTier = MenuBarSpaceProbe.selectTier(
            for: timeline.compactState,
            space: lastMeasuredSpace,
            currentTier: committedCompactTier
        )
        layoutDebounceTimer?.invalidate()
        layoutDebounceTimer = nil
        pendingCompactTier = nil
        committedCompactTier = candidateTier
        applyEffectiveCompactLayout(animated: true)
    }

    private func applyEffectiveCompactLayout(animated: Bool, fast: Bool = false) {
        let next: CompactLayout
        if isFloatingScreen {
            next = .defaultLayout
        } else {
            var resolved = CompactLayout.resolve(tier: committedCompactTier, mouseDodgedSide: mouseDodgedSide)
            if lastMeasuredSpace.overflowExpanded {
                // 系统 » 展开期间让位：两侧都收起，只剩刘海本体
                resolved = CompactLayout(leading: .hidden, trailing: .hidden, tier: committedCompactTier)
            }
            next = resolved
        }
        guard next != compactLayout else { return }

        if animated {
            let reduceMotion = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            let duration: Double = reduceMotion ? 0.12 : (fast ? 0.16 : 0.22)
            withAnimation(.easeInOut(duration: duration)) {
                compactLayout = next
            }
        } else {
            compactLayout = next
        }
    }

    private func detectMouseDodgeSide(at point: NSPoint, on screen: NSScreen) -> MouseDodgeSide {
        guard mode == .compact,
              !isFloatingScreen,
              let geometry = MenuBarSpaceProbe.notchGeometry(for: screen) else {
            return .none
        }

        let inMenuBarY = point.y >= screen.frame.maxY - geometry.menuBarHeight - 2
            && point.y <= screen.frame.maxY + 2
        guard inMenuBarY else { return .none }

        // 留出 4pt 与物理刘海边界错开，保证鼠标移到刘海本体仍能正常悬停展开
        let edgeClearance: CGFloat = 4
        let dodgeReach: CGFloat = 150
        if point.x > geometry.notchRight + edgeClearance && point.x <= geometry.notchRight + dodgeReach {
            return .right
        }
        if point.x < geometry.notchLeft - edgeClearance && point.x >= geometry.notchLeft - dodgeReach {
            return .left
        }
        return .none
    }

    private func updateMouseDodge(for point: NSPoint, on screen: NSScreen) -> MouseDodgeSide {
        let detected = detectMouseDodgeSide(at: point, on: screen)
        if detected != .none {
            dodgeRestoreTimer?.invalidate()
            dodgeRestoreTimer = nil
            if mouseDodgedSide != detected {
                mouseDodgedSide = detected
                applyEffectiveCompactLayout(animated: true, fast: true)
            }
        } else if mouseDodgedSide != .none, dodgeRestoreTimer == nil {
            // 鼠标离开菜单栏靠近区域 1 秒后再恢复该侧胶囊；恢复前补测一次空间，
            // 避免用户刚点击 Ice/Bartender 展开图标后胶囊先闪回旧档位再缩回。
            dodgeRestoreTimer = Timer(timeInterval: 1.0, repeats: false) { [weak self] _ in
                Task { @MainActor in
                    guard let self else { return }
                    self.dodgeRestoreTimer = nil
                    guard self.mode == .compact, !self.isFloatingScreen else {
                        self.mouseDodgedSide = .none
                        return
                    }
                    let current = NSEvent.mouseLocation
                    let currentScreen = self.screen(containing: current) ?? self.targetScreen()
                    let currentDetected = self.detectMouseDodgeSide(at: current, on: currentScreen)
                    if currentDetected == .none {
                        self.restoreMouseDodgeWithFreshProbe()
                    } else if self.mouseDodgedSide != currentDetected {
                        self.mouseDodgedSide = currentDetected
                        self.applyEffectiveCompactLayout(animated: true, fast: true)
                    }
                }
            }
            RunLoop.main.add(dodgeRestoreTimer!, forMode: .common)
        }
        return detected
    }

    private func restoreMouseDodgeWithFreshProbe() {
        guard mode == .compact, !isFloatingScreen else {
            mouseDodgedSide = .none
            return
        }
        spaceProbeGeneration += 1
        let generation = spaceProbeGeneration
        spaceProbe.probe(on: targetScreen()) { [weak self] space in
            guard let self,
                  generation == self.spaceProbeGeneration,
                  self.mode == .compact,
                  !self.isFloatingScreen else {
                return
            }
            let current = NSEvent.mouseLocation
            let currentScreen = self.screen(containing: current) ?? self.targetScreen()
            guard self.detectMouseDodgeSide(at: current, on: currentScreen) == .none else { return }

            self.hasMeasuredSpace = true
            self.lastMeasuredSpace = space
            self.layoutDebounceTimer?.invalidate()
            self.layoutDebounceTimer = nil
            self.pendingCompactTier = nil
            self.committedCompactTier = MenuBarSpaceProbe.selectTier(
                for: self.timeline.compactState,
                space: space,
                currentTier: self.committedCompactTier
            )
            self.mouseDodgedSide = .none
            self.applyEffectiveCompactLayout(animated: true)
        }
    }

    /// 根据当前状态请求占位宽度。展开、提示、拖放期间保持原宽度，避免图标跳动。
    private func updateNotchSpacer() {
        guard notchSpacer.isEnabled else { return }
        if isFloatingScreen || mode == .hidden || timeline.compactState == .hidden {
            notchSpacer.requestLength(0)
            return
        }
        guard mode == .compact else { return }
        // 一律按“完整文字”预留空间，确保能升级到最佳档位；当前档位是否放得下交给探测判断
        let textWidth = CompactTextMeasure.fullTextWidth(for: timeline.compactState)
        notchSpacer.requestLength(textWidth > 0 ? textWidth + NotchSpacerItem.extraWidth : 0)
    }

    func refreshNotchSpacer() {
        updateNotchSpacer()
    }

    /// 占位宽度变化后，系统重排状态栏需要一点时间，0.3 秒后再测量
    private func scheduleProbeAfterSpacerChange() {
        spacerProbeTimer?.invalidate()
        let timer = Timer(timeInterval: 0.3, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.spacerProbeTimer = nil
                guard self.mode == .compact, !self.isFloatingScreen else { return }
                self.probeMenuBarSpace(immediate: false)
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        spacerProbeTimer = timer
    }

    // MARK: - Compact / hidden state

    func refreshCompactOrHidden() {
        guard mode != .expanded, !isPromptMode else { return }
        applyCompactOrHidden()
    }

    /// 展开类模式（expanded / prompt / dropTarget）：逻辑状态和显示内容同步更新。
    private func enterPresentedMode(_ newMode: IslandPresentationMode) {
        stopSpaceProbingAndResetLayout()
        transitionID += 1
        mode = newMode
        contentMode = newMode
    }

    private func applyCompactOrHidden() {
        expandTimer?.invalidate()
        expandTimer = nil

        transitionID += 1
        let id = transitionID
        let wasShowingWeb = (contentMode == .expanded)
        let target: IslandPresentationMode = (timeline.compactState == .hidden) ? .hidden : .compact
        let screen = targetScreen()

        // 逻辑状态立刻更新；contentMode 暂不动，画面继续显示原内容
        mode = target

        if target == .compact, !isFloatingScreen {
            recomputeCompactTierForStateChange()
            startSpaceProbingIfNeeded()
            probeMenuBarSpace(immediate: !hasMeasuredSpace)
        } else {
            stopSpaceProbingAndResetLayout()
        }
        updateNotchSpacer()

        Task { @MainActor [weak self] in
            guard let self else { return }

            if wasShowingWeb {
                // 兜底：SwiftUI 的过渡不一定作用在 WKWebView 上，让网页自己跟着淡出
                self.webBridge.fadeWebView(to: 0, duration: 0.22)
            }

            if target == .hidden {
                // 库：closingAnimation 收缩 → 0.25s 后窗口淡出 0.15s → 销毁窗口，内容全程在场
                await self.notch.hide()
            } else if self.isFloatingScreen {
                // 无刘海屏：库已是 expanded 时不会再播收起动画，内容收缩由我们自己补；
                // 若库还没有窗口（启动时 / 隐藏态），交给下面的 expand 正常建窗并显示胶囊。
                if self.contentMode != .compact {
                    let animatedFromWeb = (self.contentMode == .expanded)
                    withAnimation(self.collapseAnimation) {
                        self.contentMode = .compact
                    }
                    if animatedFromWeb {
                        try? await Task.sleep(for: .seconds(self.collapseAnimationDuration))
                    }
                }
                await self.notch.expand(on: screen)
            } else {
                // 刘海屏：库把展开内容收缩并换成刘海两侧的胶囊内容
                await self.notch.compact(on: screen)
            }

            // 收起途中又被展开（或又触发了一次收起），本次收尾作废
            guard id == self.transitionID else { return }

            self.contentMode = target
            if wasShowingWeb {
                self.webBridge.attachToKeepAliveWindow()   // 动画结束后才把网页搬回后台窗口
                self.webBridge.fadeWebView(to: 1, duration: 0)   // 复位，下次展开直接可见
            }
            self.applyWindowAppearance()
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
        enterPresentedMode(.expanded)
        webBridge.showSurface(surface.rawValue)
        webBridge.fadeWebView(to: 1, duration: 0)

        let screen = targetScreen()
        Task { @MainActor [weak self] in
            guard let self else { return }
            // 窗口在动画开始前已是最终尺寸（panelSize），这里不再做任何尺寸操作。
            let expandTask = Task { await self.notch.expand(on: screen) }
            // 新建窗口同步发生在 _expand 开头，让出一轮主循环即可设置外观和焦点，不必等 0.4s。
            await Task.yield()
            self.applyWindowAppearance()
            if activateKeyboard {
                self.focusPanel()
            }
            await expandTask.value
            // 兜底：若调度顺序异常导致窗口尚未就绪，这里补齐外观与键盘焦点。
            self.applyWindowAppearance()
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

    // MARK: - Drag and drop

    func beginDropHover() {
        expandTimer?.invalidate()
        expandTimer = nil
        collapseTimer?.invalidate()
        collapseTimer = nil
        promptTimer?.invalidate()
        promptTimer = nil

        guard mode != .expanded else { return }

        enterPresentedMode(.dropTarget)
        onStateChanged?()

        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.notch.expand(on: self.targetScreen())
            self.applyWindowAppearance()
        }
    }

    func endDropHover() {
        guard mode == .dropTarget else { return }
        mode = .hidden
        refreshCompactOrHidden()
    }

    func handleDrop(text: String?, fileURL: String?) {
        expandTimer?.invalidate()
        expandTimer = nil
        collapseTimer?.invalidate()
        collapseTimer = nil
        promptTimer?.invalidate()
        promptTimer = nil

        expand(surface: .quickAdd, activateKeyboard: true, pin: true)
        webBridge.dropPayload(text: text, fileURL: fileURL)
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

        enterPresentedMode(.prompt(text: text, targetSurface: targetSurface))

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
                withAnimation(surfaceAnimation) {
                    surface = .today
                }
                webBridge.showSurface("today")
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
        Group {
            switch controller.contentMode {
            case .compact:
                HStack(spacing: 8) {
                    CompactLeadingView(controller: controller)
                    CompactTrailingView(controller: controller)
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

            case .dropTarget:
                HStack(spacing: 8) {
                    Image(systemName: "tray.and.arrow.down.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(islandTodoColor)
                    Text("松手创建待办")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)

            case .expanded:
                let size = clampedSize(for: controller.surface)
                WebViewHostRepresentable(bridge: controller.webBridge)
                    .frame(width: size.width, height: size.height)
                    .clipped()

            case .hidden:
                EmptyView()
            }
        }
        .onDrop(
            of: [.fileURL, .utf8PlainText, .plainText],
            delegate: IslandDropDelegate(controller: controller)
        )
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

struct IslandDropDelegate: DropDelegate {
    let controller: IslandController

    func validateDrop(info: DropInfo) -> Bool {
        info.hasItemsConforming(to: [.fileURL, .utf8PlainText, .plainText])
    }

    func dropEntered(info: DropInfo) {
        Task { @MainActor in
            controller.beginDropHover()
        }
    }

    func dropExited(info: DropInfo) {
        Task { @MainActor in
            controller.endDropHover()
        }
    }

    func performDrop(info: DropInfo) -> Bool {
        let providers = info.itemProviders(for: [.fileURL, .utf8PlainText, .plainText])

        if let provider = providers.first(where: {
            $0.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier)
        }) {
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                guard let url = Self.resolveFileURL(from: item) else { return }
                Task { @MainActor in
                    controller.handleDrop(text: nil, fileURL: url.absoluteString)
                }
            }
            return true
        }

        let textTypes = [UTType.utf8PlainText, UTType.plainText]
        let textProvider = providers.first(where: { provider in
            textTypes.contains(where: { provider.hasItemConformingToTypeIdentifier($0.identifier) })
        })
        if let provider = textProvider,
           let type = textTypes.first(where: { provider.hasItemConformingToTypeIdentifier($0.identifier) }) {
            provider.loadItem(forTypeIdentifier: type.identifier, options: nil) { item, _ in
                guard let text = Self.resolveText(from: item) else { return }
                Task { @MainActor in
                    controller.handleDrop(text: text, fileURL: nil)
                }
            }
            return true
        }

        return false
    }

    private static func resolveFileURL(from item: NSSecureCoding?) -> URL? {
        if let url = item as? URL {
            return url
        }
        if let url = item as? NSURL {
            return url as URL
        }
        if let data = item as? Data {
            if let url = URL(dataRepresentation: data, relativeTo: nil) {
                return url
            }
            if let raw = String(data: data, encoding: .utf8) {
                if let url = URL(string: raw) {
                    return url
                }
                if raw.hasPrefix("/") {
                    return URL(fileURLWithPath: raw)
                }
            }
        }
        if let raw = item as? String {
            if let url = URL(string: raw) {
                return url
            }
            if raw.hasPrefix("/") {
                return URL(fileURLWithPath: raw)
            }
        }
        return nil
    }

    private static func resolveText(from item: NSSecureCoding?) -> String? {
        if let text = item as? String {
            return text
        }
        if let data = item as? Data, let text = String(data: data, encoding: .utf8) {
            return text
        }
        if let attributed = item as? NSAttributedString {
            return attributed.string
        }
        return nil
    }
}
