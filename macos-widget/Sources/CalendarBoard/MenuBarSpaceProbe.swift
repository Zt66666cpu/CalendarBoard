import AppKit
import ApplicationServices
import CoreGraphics

/// 刘海左右两侧当前可摆放胶囊内容的宽度（单位：pt，已扣除安全边距与库内边距）。
struct MenuBarSpace: Equatable, Sendable {
    let leftAvailable: CGFloat
    let rightAvailable: CGFloat
    let hasAccessibilityPermission: Bool
    /// 刘海右侧最左一个外部状态栏图标的 minX（与 AppKit X 轴相同，可直接比较）。
    let leftmostForeignStatusMinX: CGFloat?
    /// 系统菜单栏 » 折叠区是否处于展开态（展开时被折叠的图标会出现在刘海左侧）。
    let overflowExpanded: Bool

    /// 无刘海屏或首次探测前的宽松默认值。
    static let unconstrained = MenuBarSpace(
        leftAvailable: 1000,
        rightAvailable: 1000,
        hasAccessibilityPermission: true,
        leftmostForeignStatusMinX: nil,
        overflowExpanded: false
    )
}

/// 目标屏与刘海的几何快照（用于在后台队列安全地做坐标换算与探测）。
struct ScreenNotchGeometry: Sendable {
    let screenFrame: CGRect
    let primaryScreenHeight: CGFloat
    let notchLeft: CGFloat
    let notchRight: CGFloat
    let menuBarHeight: CGFloat

    /// 目标屏顶部在 CoreGraphics / AX（原点在主屏左上角，Y 向下）坐标系中的 Y 值。
    var screenTopCG: CGFloat {
        primaryScreenHeight - screenFrame.maxY
    }
}

/// 紧凑胶囊的渲染方案：由可用空间档位与鼠标临时避让共同决定。
struct CompactLayout: Equatable {
    enum LeadingContent: Equatable {
        case hidden           // 鼠标靠近左侧菜单栏时临时隐藏
        case iconOnly         // 仅显示图标（上课态为 18pt 进度环 + 书本）
        case iconAndFullText  // 图标 + 完整文字（右侧无空间、左侧宽裕时）
        case fullTextOnly     // 仅显示完整文字（与右侧图标互换位置时）
    }

    enum TrailingContent: Equatable {
        case hidden           // 右侧留空
        case iconOnly         // 仅显示图标（与左侧完整文字互换位置时）
        case shortText        // 短写（如 "15′"、"14点"）
        case fullText         // 完整文字（如 "剩15分"）
    }

    /// 空间自适应的基础档位（按优先级从高到低排列）。
    enum Tier: Int, Equatable, CaseIterable {
        case splitFull = 0     // a. 左图标，右完整文字
        case leftCombined = 1  // b. 左图标+完整文字，右空
        case swapped = 2       // c. 左完整文字，右图标
        case splitShort = 3    // d. 左图标，右短写
        case leftIconOnly = 4  // e. 仅左图标，右空
    }

    let leading: LeadingContent
    let trailing: TrailingContent
    let tier: Tier

    static let defaultLayout = CompactLayout(leading: .iconOnly, trailing: .fullText, tier: .splitFull)

    static func resolve(tier: Tier, mouseDodgedSide: MouseDodgeSide) -> CompactLayout {
        let baseLeading: LeadingContent
        let baseTrailing: TrailingContent

        switch tier {
        case .splitFull:
            baseLeading = .iconOnly
            baseTrailing = .fullText
        case .leftCombined:
            baseLeading = .iconAndFullText
            baseTrailing = .hidden
        case .swapped:
            baseLeading = .fullTextOnly
            baseTrailing = .iconOnly
        case .splitShort:
            baseLeading = .iconOnly
            baseTrailing = .shortText
        case .leftIconOnly:
            baseLeading = .iconOnly
            baseTrailing = .hidden
        }

        switch mouseDodgedSide {
        case .none:
            return CompactLayout(leading: baseLeading, trailing: baseTrailing, tier: tier)
        case .left:
            return CompactLayout(leading: .hidden, trailing: baseTrailing, tier: tier)
        case .right:
            return CompactLayout(leading: baseLeading, trailing: .hidden, tier: tier)
        }
    }
}

/// 鼠标在菜单栏靠近刘海某一侧时的临时避让方向。
enum MouseDodgeSide: Equatable {
    case none
    case left
    case right
}

/// 各 compactState 下胶囊内容块的估算宽度（pt）。
struct CompactStateMetrics {
    let iconWidth: CGFloat
    let fullTextWidth: CGFloat
    let shortTextWidth: CGFloat
    let supportsShortText: Bool
    let combinedSpacing: CGFloat = 4

    var combinedLeftWidth: CGFloat {
        iconWidth + combinedSpacing + fullTextWidth
    }

    static func forState(_ state: CompactDisplayState) -> CompactStateMetrics {
        let fullWidth = CompactTextMeasure.fullTextWidth(for: state)
        switch state {
        case .inClass:
            // 环+图标约 20pt，完整文字按真实字体测量，短写“N′”约 28pt
            return CompactStateMetrics(iconWidth: 20, fullTextWidth: fullWidth, shortTextWidth: 28, supportsShortText: true)
        case .upcomingSoon:
            // 图标约 18pt，完整文字按真实字体测量，短写“N′”约 24pt
            return CompactStateMetrics(iconWidth: 18, fullTextWidth: fullWidth, shortTextWidth: 24, supportsShortText: true)
        case .upcomingLater(let startText, _):
            // 图标约 18pt，完整文字按真实字体测量；整点短写“H点”约 28pt，非整点缩小字号约 34pt
            let isWholeHour = startText.hasSuffix(":00")
            return CompactStateMetrics(
                iconWidth: 18,
                fullTextWidth: fullWidth,
                shortTextWidth: isWholeHour ? 28 : 34,
                supportsShortText: true
            )
        case .todosOnly:
            // 图标约 18pt，数字按真实字体测量（已足够紧凑，无需额外短写档）
            return CompactStateMetrics(iconWidth: 18, fullTextWidth: fullWidth, shortTextWidth: 20, supportsShortText: false)
        case .hidden:
            return CompactStateMetrics(iconWidth: 0, fullTextWidth: 0, shortTextWidth: 0, supportsShortText: false)
        }
    }
}

/// 探测刘海左右两侧菜单栏的剩余可用空间，并根据回差规则选择布局档位。
final class MenuBarSpaceProbe {
    /// 与状态栏图标 / 菜单标题之间保留的安全边距。
    /// 取接近系统状态栏图标之间的间距，让胶囊和相邻图标的空隙看起来像"原生的一员"。
    static let safetyMargin: CGFloat = 12
    /// DynamicNotchKit 在 compactLeading / compactTrailing 外侧补的 8pt safeAreaInset，
    /// 再加 NotchView 的 .padding(.horizontal, topCornerRadius)（compact 态 top 圆角 = 6）。
    static let librarySideInset: CGFloat = 8 + 6 + notchInnerGap
    /// 胶囊内容与物理刘海边缘之间的间隙（CompactViews 在靠刘海一侧补的 padding）。
    static let notchInnerGap: CGFloat = 6
    /// 无辅助功能权限时左侧的保守值：仅放得下图标，不会把文字挤到左侧盖住应用菜单。
    static let conservativeLeftWidth: CGFloat = 20
    /// 档位升级回差，避免在临界宽度附近来回抖动。
    static let hysteresisMargin: CGFloat = 10

    private let queue = DispatchQueue(label: "com.calendarboard.menubar-space-probe", qos: .utility)

    /// 提取目标屏的刘海几何信息；无刘海屏返回 nil。
    @MainActor
    static func notchGeometry(for screen: NSScreen) -> ScreenNotchGeometry? {
        guard let leftArea = screen.auxiliaryTopLeftArea,
              let rightArea = screen.auxiliaryTopRightArea else {
            return nil
        }
        let notchWidth = max(0, screen.frame.width - leftArea.width - rightArea.width)
        guard notchWidth > 0 else { return nil }

        let notchLeft = screen.frame.midX - notchWidth / 2
        let notchRight = screen.frame.midX + notchWidth / 2
        let menuBarHeight = max(24, max(screen.safeAreaInsets.top, screen.frame.maxY - screen.visibleFrame.maxY))
        let primaryHeight = NSScreen.screens.first(where: { $0.frame.origin == .zero })?.frame.height
            ?? NSScreen.screens.first?.frame.height
            ?? screen.frame.height

        return ScreenNotchGeometry(
            screenFrame: screen.frame,
            primaryScreenHeight: primaryHeight,
            notchLeft: notchLeft,
            notchRight: notchRight,
            menuBarHeight: menuBarHeight
        )
    }

    /// 在后台队列异步测量刘海两侧空间，避免偶发的 AX IPC 阻塞主线程。
    @MainActor
    func probe(on screen: NSScreen, completion: @escaping @MainActor (MenuBarSpace) -> Void) {
        guard let geometry = Self.notchGeometry(for: screen) else {
            completion(.unconstrained)
            return
        }

        let ownPID = ProcessInfo.processInfo.processIdentifier
        let frontmostPID = NSWorkspace.shared.frontmostApplication?.processIdentifier ?? 0
        let pids = NSWorkspace.shared.runningApplications.map(\.processIdentifier)

        queue.async {
            let space = Self.measureSync(
                geometry: geometry,
                frontmostPID: frontmostPID,
                pids: pids,
                ownPID: ownPID
            )
            Task { @MainActor in
                completion(space)
            }
        }
    }

    /// 按优先级（a→b→c→d→e）选择布局档位，升级到更宽档位时要求额外的 10pt 回差。
    static func selectTier(
        for state: CompactDisplayState,
        space: MenuBarSpace,
        currentTier: CompactLayout.Tier,
        hysteresis: CGFloat = hysteresisMargin
    ) -> CompactLayout.Tier {
        let metrics = CompactStateMetrics.forState(state)
        guard metrics.iconWidth > 0 else { return .splitFull }

        func fits(_ tier: CompactLayout.Tier, margin: CGFloat) -> Bool {
            switch tier {
            case .splitFull:
                return space.rightAvailable >= metrics.fullTextWidth + margin
            case .leftCombined:
                return space.leftAvailable >= metrics.combinedLeftWidth + margin
            case .swapped:
                return space.leftAvailable >= metrics.fullTextWidth + margin
                    && space.rightAvailable >= metrics.iconWidth + margin
            case .splitShort:
                return metrics.supportsShortText && space.rightAvailable >= metrics.shortTextWidth + margin
            case .leftIconOnly:
                return true
            }
        }

        // 1. 优先尝试升级到更优档位，必须额外满足回差宽度
        for tier in CompactLayout.Tier.allCases where tier.rawValue < currentTier.rawValue {
            if fits(tier, margin: hysteresis) {
                return tier
            }
        }

        // 2. 当前档位仍能放下则保持不变
        if fits(currentTier, margin: 0) {
            return currentTier
        }

        // 3. 当前档位已放不下，按顺序降级到第一个能放下的方案
        for tier in CompactLayout.Tier.allCases {
            if fits(tier, margin: 0) {
                return tier
            }
        }
        return .leftIconOnly
    }

    // MARK: - Measurement internals

    private static func measureSync(
        geometry: ScreenNotchGeometry,
        frontmostPID: pid_t,
        pids: [pid_t],
        ownPID: pid_t
    ) -> MenuBarSpace {
        let rightResult = measureRightAvailableAX(geometry: geometry, pids: pids, ownPID: ownPID)
            ?? measureRightAvailable(geometry: geometry, ownPID: ownPID)
        let hasAX = AXIsProcessTrusted()
        let leftAvailable: CGFloat
        if hasAX {
            leftAvailable = measureLeftAvailable(
                geometry: geometry,
                frontmostPID: frontmostPID,
                ownPID: ownPID
            )
        } else {
            leftAvailable = conservativeLeftWidth
        }

        return MenuBarSpace(
            leftAvailable: leftAvailable,
            rightAvailable: rightResult.available,
            hasAccessibilityPermission: hasAX,
            leftmostForeignStatusMinX: rightResult.minStatusX,
            overflowExpanded: rightResult.overflowExpanded
        )
    }

    private static func axFrame(of element: AXUIElement) -> CGRect? {
        var posRef: CFTypeRef?
        var sizeRef: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, kAXPositionAttribute as CFString, &posRef) == .success,
              AXUIElementCopyAttributeValue(element, kAXSizeAttribute as CFString, &sizeRef) == .success,
              let posRef, let sizeRef,
              CFGetTypeID(posRef) == AXValueGetTypeID(),
              CFGetTypeID(sizeRef) == AXValueGetTypeID() else { return nil }
        var point = CGPoint.zero
        var size = CGSize.zero
        guard AXValueGetValue(posRef as! AXValue, .cgPoint, &point),
              AXValueGetValue(sizeRef as! AXValue, .cgSize, &size) else { return nil }
        return CGRect(origin: point, size: size)
    }

    /// 右侧：用辅助功能读取所有应用的状态栏项。
    /// macOS 26 起状态栏图标窗口由系统托管，CGWindowList 看不到，AX 仍能拿到真实位置。
    /// 返回 nil 表示没有 AX 权限，调用方退回 CGWindowList。
    private static func measureRightAvailableAX(
        geometry: ScreenNotchGeometry,
        pids: [pid_t],
        ownPID: pid_t
    ) -> (available: CGFloat, minStatusX: CGFloat?, overflowExpanded: Bool)? {
        guard AXIsProcessTrusted() else { return nil }
        let topCG = geometry.screenTopCG
        let bottomCG = topCG + geometry.menuBarHeight
        var frames: [CGRect] = []
        var leftSideForeignCount = 0

        for pid in pids where pid != ownPID {
            let app = AXUIElementCreateApplication(pid)
            AXUIElementSetMessagingTimeout(app, 0.05)
            var barRef: CFTypeRef?
            guard AXUIElementCopyAttributeValue(app, kAXExtrasMenuBarAttribute as CFString, &barRef) == .success,
                  let barRef, CFGetTypeID(barRef) == AXUIElementGetTypeID() else { continue }
            var kidsRef: CFTypeRef?
            guard AXUIElementCopyAttributeValue(barRef as! AXUIElement, kAXChildrenAttribute as CFString, &kidsRef) == .success,
                  let items = kidsRef as? [AXUIElement] else { continue }
            for item in items {
                guard let f = axFrame(of: item) else { continue }
                if f.width >= 2, f.height >= 2,
                   f.minY >= topCG - 2, f.maxY <= bottomCG + 6,
                   f.maxX <= geometry.notchLeft + 2,
                   f.minX >= geometry.screenFrame.minX {
                    // 刘海屏上状态栏图标正常不会出现在刘海左侧，出现即说明 » 已展开
                    leftSideForeignCount += 1
                    continue
                }
                guard f.width >= 2, f.height >= 2,
                      f.minY >= topCG - 2, f.maxY <= bottomCG + 6,
                      f.maxX > geometry.notchRight + 2,          // 在刘海右半区（含跨刘海的）
                      f.minX < geometry.screenFrame.maxX else { continue }
                frames.append(f)
            }
        }
        // 看不见任何图标时按"没空间"处理，绝不盖住看不见的东西
        guard !frames.isEmpty else { return (available: 0, minStatusX: nil, overflowExpanded: leftSideForeignCount > 0) }

        // 被收进 » 的图标会叠成一簇（x 互相重叠），可见的只有簇最右端的 » 箭头
        var clusters: [[CGRect]] = []
        for f in frames.sorted(by: { $0.minX < $1.minX }) {
            if let last = clusters.last, let lastMaxX = last.map(\.maxX).max(), f.minX < lastMaxX - 2 {
                clusters[clusters.count - 1].append(f)
            } else {
                clusters.append([f])
            }
        }
        let visibleLeftEdges: [CGFloat] = clusters.map { cluster in
            if cluster.count == 1 { return cluster[0].minX }
            let maxX = cluster.map(\.maxX).max()!
            // 箭头 = 右边缘对齐簇右边缘、宽度最窄的那个
            let chevron = cluster
                .filter { abs($0.maxX - maxX) <= 1.5 }
                .min(by: { $0.width < $1.width })
            return chevron?.minX ?? (maxX - 18)
        }
        let leftmost = max(geometry.notchRight, visibleLeftEdges.min()!)
        let available = leftmost - geometry.notchRight - safetyMargin - librarySideInset
        return (available: max(0, available), minStatusX: leftmost, overflowExpanded: leftSideForeignCount > 0)
    }

    /// 右侧：扫描状态栏层窗口，找出目标屏菜单栏内位于刘海右侧的最左外部窗口 minX。
    private static func measureRightAvailable(
        geometry: ScreenNotchGeometry,
        ownPID: pid_t
    ) -> (available: CGFloat, minStatusX: CGFloat?, overflowExpanded: Bool) {
        let statusLevel = Int(CGWindowLevelForKey(.statusWindow))
        let topCG = geometry.screenTopCG
        let bottomCG = topCG + geometry.menuBarHeight

        var minStatusX: CGFloat?

        if let windowList = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID) as? [[String: Any]] {
            for info in windowList {
                guard let layer = info[kCGWindowLayer as String] as? Int,
                      layer == statusLevel else {
                    continue
                }
                if let ownerPID = info[kCGWindowOwnerPID as String] as? pid_t,
                   ownerPID == ownPID {
                    continue
                }
                if let alpha = info[kCGWindowAlpha as String] as? Double,
                   alpha <= 0.01 {
                    continue
                }
                guard let boundsDict = info[kCGWindowBounds as String] as? [String: Any],
                      let bounds = CGRect(dictionaryRepresentation: boundsDict as CFDictionary) else {
                    continue
                }

                // 排除零尺寸或超出目标屏菜单栏高度的弹出窗口
                guard bounds.width >= 2,
                      bounds.height >= 2,
                      bounds.minY >= topCG - 2,
                      bounds.maxY <= bottomCG + 6 else {
                    continue
                }

                // 只统计位于目标屏内且在刘海右边缘右侧的状态栏图标（留 2pt 容差防止贴边漏检）
                guard bounds.minX >= geometry.notchRight - 2,
                      bounds.minX < geometry.screenFrame.maxX else {
                    continue
                }

                if let currentMin = minStatusX {
                    minStatusX = min(currentMin, bounds.minX)
                } else {
                    minStatusX = bounds.minX
                }
            }
        }

        // 一个状态栏图标都没扫到 ≠ 右侧是空的。macOS 26 起状态栏图标改由 Control Center 托管，
        // 且无屏幕录制权限时 CGWindowList 往往看不到它们（实测只返回 WindowServer 菜单栏和本进程窗口）。
        // 刘海屏右侧几乎总有图标，所以"看不见"按"没空间"处理：内容会移到左侧，绝不盖住看不见的图标。
        guard let minStatusX else {
            return (available: 0, minStatusX: nil, overflowExpanded: false)
        }
        let available = minStatusX - geometry.notchRight - safetyMargin - librarySideInset
        return (available: max(0, available), minStatusX: minStatusX, overflowExpanded: false)
    }

    /// 左侧：通过辅助功能读取前台应用菜单栏最后一个菜单项的右边缘。
    private static func measureLeftAvailable(
        geometry: ScreenNotchGeometry,
        frontmostPID: pid_t,
        ownPID: pid_t
    ) -> CGFloat {
        guard frontmostPID > 0, frontmostPID != ownPID else {
            return conservativeLeftWidth
        }

        let appElement = AXUIElementCreateApplication(frontmostPID)
        AXUIElementSetMessagingTimeout(appElement, 0.15)

        var menuBarRef: CFTypeRef?
        guard AXUIElementCopyAttributeValue(appElement, kAXMenuBarAttribute as CFString, &menuBarRef) == .success,
              let menuBarObj = menuBarRef,
              CFGetTypeID(menuBarObj) == AXUIElementGetTypeID() else {
            return conservativeLeftWidth
        }
        let menuBarElement = menuBarObj as! AXUIElement

        var childrenRef: CFTypeRef?
        guard AXUIElementCopyAttributeValue(menuBarElement, kAXChildrenAttribute as CFString, &childrenRef) == .success,
              let children = childrenRef as? [AXUIElement],
              !children.isEmpty else {
            return conservativeLeftWidth
        }

        let topCG = geometry.screenTopCG
        let bottomCG = topCG + geometry.menuBarHeight
        var maxMenuRight: CGFloat?

        for child in children {
            guard let pos = axPoint(child, attribute: kAXPositionAttribute),
                  let size = axSize(child, attribute: kAXSizeAttribute),
                  size.width > 0,
                  size.height > 0 else {
                continue
            }
            guard pos.y >= topCG - 4,
                  pos.y <= bottomCG + 4,
                  pos.x >= geometry.screenFrame.minX - 2,
                  pos.x < geometry.notchRight else {
                continue
            }
            let itemRight = pos.x + size.width
            if let currentMax = maxMenuRight {
                maxMenuRight = max(currentMax, itemRight)
            } else {
                maxMenuRight = itemRight
            }
        }

        guard let menuRight = maxMenuRight else {
            return conservativeLeftWidth
        }

        let available = geometry.notchLeft - menuRight - safetyMargin - librarySideInset
        return max(0, available)
    }

    private static func axPoint(_ element: AXUIElement, attribute: String) -> CGPoint? {
        var ref: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, attribute as CFString, &ref) == .success,
              let valueObj = ref,
              CFGetTypeID(valueObj) == AXValueGetTypeID() else {
            return nil
        }
        let axValue = valueObj as! AXValue
        var point = CGPoint.zero
        guard AXValueGetType(axValue) == .cgPoint,
              AXValueGetValue(axValue, .cgPoint, &point) else {
            return nil
        }
        return point
    }

    private static func axSize(_ element: AXUIElement, attribute: String) -> CGSize? {
        var ref: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, attribute as CFString, &ref) == .success,
              let valueObj = ref,
              CFGetTypeID(valueObj) == AXValueGetTypeID() else {
            return nil
        }
        let axValue = valueObj as! AXValue
        var size = CGSize.zero
        guard AXValueGetType(axValue) == .cgSize,
              AXValueGetValue(axValue, .cgSize, &size) else {
            return nil
        }
        return size
    }
}
