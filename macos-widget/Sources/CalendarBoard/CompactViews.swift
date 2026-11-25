import SwiftUI
import UniformTypeIdentifiers

let islandCourseColor = Color(red: 0x7D / 255.0, green: 0xD3 / 255.0, blue: 0xFC / 255.0)
let islandTodoColor = Color(red: 0xFB / 255.0, green: 0xBF / 255.0, blue: 0x24 / 255.0)
let islandSecondaryText = Color.white.opacity(0.64)

private let islandDropTypes: [UTType] = [.fileURL, .utf8PlainText, .plainText]

struct CompactLeadingView: View {
    @ObservedObject var controller: IslandController
    @ObservedObject var timeline: IslandTimeline

    init(controller: IslandController) {
        self._controller = ObservedObject(wrappedValue: controller)
        self._timeline = ObservedObject(wrappedValue: controller.timeline)
    }

    @ViewBuilder
    var body: some View {
        if controller.compactLayout.leading == .hidden || timeline.compactState == .hidden {
            EmptyView()
        } else {
            content
                // 库只在外侧补 8pt，靠刘海一侧是 0；物理刘海下缘是圆角，内容贴边会被"咬"掉一角
                .padding(.trailing, MenuBarSpaceProbe.notchInnerGap)
                .onDrop(of: islandDropTypes, delegate: IslandDropDelegate(controller: controller))
                .transition(.opacity)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch controller.compactLayout.leading {
        case .hidden:
            EmptyView()
        case .iconOnly:
            compactIconView(for: timeline.compactState, showsRemainingInRing: showsRemainingInRing)
                .transition(.opacity)
        case .iconAndFullText:
            // 右侧空间不足、左侧宽裕时，把完整文字移到左侧与图标并排显示
            HStack(spacing: 4) {
                compactIconView(for: timeline.compactState, showsRemainingInRing: showsRemainingInRing)
                compactFullTextView(for: timeline.compactState)
            }
            .fixedSize()
            .transition(.opacity)
        case .fullTextOnly:
            compactFullTextView(for: timeline.compactState)
                .transition(.opacity)
        }
    }

    /// 仅左侧显示图标（无文字）时，上课态进度环内直接显示剩余分钟数。
    private var showsRemainingInRing: Bool {
        controller.compactLayout.tier == .leftIconOnly
            || (controller.compactLayout.leading == .iconOnly && controller.compactLayout.trailing == .hidden)
    }
}

struct CompactTrailingView: View {
    @ObservedObject var controller: IslandController
    @ObservedObject var timeline: IslandTimeline

    init(controller: IslandController) {
        self._controller = ObservedObject(wrappedValue: controller)
        self._timeline = ObservedObject(wrappedValue: controller.timeline)
    }

    @ViewBuilder
    var body: some View {
        if controller.compactLayout.trailing == .hidden || timeline.compactState == .hidden {
            EmptyView()
        } else {
            content
                .padding(.leading, MenuBarSpaceProbe.notchInnerGap)   // 同上，离开刘海右缘
                .onDrop(of: islandDropTypes, delegate: IslandDropDelegate(controller: controller))
                .transition(.opacity)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch controller.compactLayout.trailing {
        case .hidden:
            EmptyView()
        case .iconOnly:
            compactIconView(for: timeline.compactState)
                .transition(.opacity)
        case .fullText:
            compactFullTextView(for: timeline.compactState)
                .transition(.opacity)
        case .shortText:
            compactShortTextView(for: timeline.compactState)
                .transition(.opacity)
        }
    }
}

@ViewBuilder
private func compactIconView(
    for state: CompactDisplayState,
    showsRemainingInRing: Bool = false
) -> some View {
    switch state {
    case .inClass(let remaining, let progress, _):
        // 进度环包住图标：不额外占横向空间，另一侧只需放文字
        ZStack {
            // inset(by: 1)：描边居中画在路径上，不内缩会向外溢出 1pt，超出 18pt 的框
            Circle()
                .inset(by: 1)
                .stroke(Color.white.opacity(0.22), lineWidth: 2)
            Circle()
                .inset(by: 1)
                .trim(from: 0, to: CGFloat(min(1.0, max(0.02, progress))))
                .stroke(islandCourseColor, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
            if showsRemainingInRing {
                Text("\(remaining)")
                    .font(.system(size: remaining >= 100 ? 7 : 9, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(islandCourseColor)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                    .frame(maxWidth: 12)
                    .contentTransition(.numericText())
                    .transition(.opacity)
            } else {
                Image(systemName: "book.closed.fill")
                    .font(.system(size: 8, weight: .semibold))
                    .foregroundStyle(islandCourseColor)
                    .transition(.opacity)
            }
        }
        .frame(width: 18, height: 18)
        .animation(.linear(duration: 0.3), value: progress)
        .animation(.easeInOut(duration: 0.2), value: remaining)
        .animation(.easeInOut(duration: 0.2), value: showsRemainingInRing)
    case .upcomingSoon:
        Image(systemName: "book.closed.fill")
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(islandCourseColor)
    case .upcomingLater:
        Image(systemName: "book.closed.fill")
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(islandSecondaryText)
    case .todosOnly:
        Image(systemName: "checkmark.circle")
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(islandTodoColor)
    case .hidden:
        EmptyView()
    }
}

@ViewBuilder
private func compactFullTextView(for state: CompactDisplayState) -> some View {
    switch state {
    case .inClass(let remaining, _, _):
        // 进度已移到左侧图标外环
        Text("剩\(remaining)分")
            .font(.system(size: 12, weight: .medium))
            .monospacedDigit()
            .lineLimit(1)
            .foregroundStyle(.white)
    case .upcomingSoon(let minutes, _):
        Text("\(minutes)分")
            .font(.system(size: 12, weight: .medium))
            .monospacedDigit()
            .lineLimit(1)
            .foregroundStyle(islandCourseColor)
    case .upcomingLater(let startText, _):
        Text(startText)
            .font(.system(size: 12, weight: .medium))
            .monospacedDigit()
            .lineLimit(1)
            .foregroundStyle(islandSecondaryText)
    case .todosOnly(let count):
        Text("\(count)")
            .font(.system(size: 12, weight: .medium))
            .monospacedDigit()
            .lineLimit(1)
            .foregroundStyle(islandTodoColor)
    case .hidden:
        EmptyView()
    }
}

@ViewBuilder
private func compactShortTextView(for state: CompactDisplayState) -> some View {
    switch state {
    case .inClass(let remaining, _, _):
        Text("\(remaining)′")
            .font(.system(size: 12, weight: .medium))
            .monospacedDigit()
            .lineLimit(1)
            .foregroundStyle(.white)
    case .upcomingSoon(let minutes, _):
        Text("\(minutes)′")
            .font(.system(size: 12, weight: .medium))
            .monospacedDigit()
            .lineLimit(1)
            .foregroundStyle(islandCourseColor)
    case .upcomingLater(let startText, _):
        if startText.hasSuffix(":00") {
            let hour = Int(startText.prefix(2)).map(String.init) ?? String(startText.prefix(2))
            Text("\(hour)点")
                .font(.system(size: 12, weight: .medium))
                .monospacedDigit()
                .lineLimit(1)
                .foregroundStyle(islandSecondaryText)
        } else {
            Text(startText)
                .font(.system(size: 11, weight: .medium))
                .monospacedDigit()
                .lineLimit(1)
                .foregroundStyle(islandSecondaryText)
        }
    case .todosOnly(let count):
        Text("\(count)")
            .font(.system(size: 12, weight: .medium))
            .monospacedDigit()
            .lineLimit(1)
            .foregroundStyle(islandTodoColor)
    case .hidden:
        EmptyView()
    }
}
