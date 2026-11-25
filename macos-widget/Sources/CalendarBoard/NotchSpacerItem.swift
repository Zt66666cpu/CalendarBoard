import AppKit

/// 为刘海右侧胶囊占位的透明状态栏项：让系统把其他图标往右排，而不是被胶囊盖住。
/// 本进程窗口会被 MenuBarSpaceProbe 排除，所以占位生效后，现有分档逻辑无需任何特殊分支。
@MainActor
final class NotchSpacerItem: NSObject {
    static let autosaveName = "CalendarBoardNotchSpacer"
    static let enabledDefaultsKey = "reserveNotchSpace"
    static var preferredPositionKey: String { "NSStatusItem Preferred Position \(autosaveName)" }

    /// 占位宽度 = 文字真实宽度 + extraWidth。
    /// 其中包含：safetyMargin(12) + librarySideInset(14，即库的 8pt 内边距 + 6pt 圆角外扩) + hysteresisMargin(10)。
    /// 加上回差，是为了让 selectTier 的“升级需额外 10pt”条件在占位生效时一定能满足。
    static var extraWidth: CGFloat {
        MenuBarSpaceProbe.safetyMargin + MenuBarSpaceProbe.librarySideInset + MenuBarSpaceProbe.hysteresisMargin
    }

    private var statusItem: NSStatusItem?
    private(set) var appliedLength: CGFloat = 0
    private var pendingLength: CGFloat?
    private var debounceTimer: Timer?
    private var lastRepositionAt: Date = .distantPast

    var onClick: (() -> Void)?
    /// 宽度真正写入 statusItem 后回调；控制器据此延迟重测空间（状态栏重排需要一点时间）
    var onLengthApplied: (() -> Void)?

    var isEnabled: Bool {
        get { UserDefaults.standard.object(forKey: Self.enabledDefaultsKey) as? Bool ?? true }
        set {
            UserDefaults.standard.set(newValue, forKey: Self.enabledDefaultsKey)
            if newValue { install() } else { uninstall() }
            onLengthApplied?()
        }
    }

    func installIfEnabled() {
        if isEnabled { install() }
    }

    private func install() {
        guard statusItem == nil else { return }
        let item = NSStatusBar.system.statusItem(withLength: 0)
        item.autosaveName = Self.autosaveName
        if let button = item.button {
            button.title = ""
            button.image = nil
            button.isBordered = false
            button.setAccessibilityLabel("课表灵动岛")
            button.toolTip = nil
            button.target = self
            button.action = #selector(handleClick)
        }
        statusItem = item
        appliedLength = 0
    }

    private func uninstall() {
        debounceTimer?.invalidate()
        debounceTimer = nil
        pendingLength = nil
        if let item = statusItem {
            NSStatusBar.system.removeStatusItem(item)
        }
        statusItem = nil
        appliedLength = 0
    }

    @objc private func handleClick() {
        onClick?()
    }

    /// 请求占位宽度。
    /// 变化小于 4pt 时忽略（归零除外）；变宽立即生效（先腾出空间再放内容）；变窄防抖 0.5 秒（避免图标来回跳）。
    func requestLength(_ length: CGFloat) {
        guard let item = statusItem else { return }
        let target = max(0, length.rounded(.up))
        let isZeroing = (target == 0 && appliedLength != 0)
        if abs(target - appliedLength) < 4 && !isZeroing {
            debounceTimer?.invalidate()
            debounceTimer = nil
            pendingLength = nil
            return
        }
        if target > appliedLength {
            debounceTimer?.invalidate()
            debounceTimer = nil
            pendingLength = nil
            apply(target, to: item)
            return
        }
        guard pendingLength != target else { return }
        pendingLength = target
        debounceTimer?.invalidate()
        let timer = Timer(timeInterval: 0.5, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, let item = self.statusItem, let pending = self.pendingLength else { return }
                self.debounceTimer = nil
                self.pendingLength = nil
                self.apply(pending, to: item)
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        debounceTimer = timer
    }

    private func apply(_ length: CGFloat, to item: NSStatusItem) {
        item.length = length
        appliedLength = length
        onLengthApplied?()
    }

    /// 占位项当前的窗口 frame（AppKit 全局坐标，原点在左下角）
    var windowFrame: CGRect? {
        guard let window = statusItem?.button?.window, window.isVisible else { return nil }
        return window.frame
    }

    /// 占位是否真正生效：宽度大于 0，完整位于刘海右侧、目标屏内、菜单栏高度内。仅用于日志和重排判断。
    func isEffective(on geometry: ScreenNotchGeometry) -> Bool {
        guard appliedLength > 0, let frame = windowFrame else { return false }
        let menuBarBottom = geometry.screenFrame.maxY - geometry.menuBarHeight - 2
        return frame.minX >= geometry.notchRight - 1
            && frame.maxX <= geometry.screenFrame.maxX + 1
            && frame.minY >= menuBarBottom
    }

    /// 后启动的应用，其图标会被系统插到最左端（夹在刘海和占位项之间），会被胶囊盖住。
    /// 检测到这种情况时，把占位项重新放回最左端。30 秒内最多做一次，防止死循环。
    /// 注意："NSStatusItem Preferred Position <autosaveName>" 的值是距屏幕右边缘的距离，值越大越靠左。
    ///       这个写法来自社区经验，必须实测；若 macOS 不认这个值，就删掉本函数和它的调用。
    func ensureLeftmost(foreignLeftmostMinX: CGFloat?, geometry: ScreenNotchGeometry) {
        guard isEnabled, appliedLength > 0,
              let foreignX = foreignLeftmostMinX,
              let frame = windowFrame,
              foreignX >= geometry.notchRight - 2,
              foreignX < frame.minX - 1,
              Date().timeIntervalSince(lastRepositionAt) > 30 else { return }
        lastRepositionAt = Date()
        let length = appliedLength
        uninstall()
        let position = (geometry.screenFrame.maxX - foreignX) + length + 20
        UserDefaults.standard.set(Double(position), forKey: Self.preferredPositionKey)
        install()
        if let item = statusItem {
            apply(length, to: item)
        }
    }
}
