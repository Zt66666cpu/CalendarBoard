import SwiftUI
import UniformTypeIdentifiers

let islandCourseColor = Color(red: 0x7D / 255.0, green: 0xD3 / 255.0, blue: 0xFC / 255.0)
let islandTodoColor = Color(red: 0xFB / 255.0, green: 0xBF / 255.0, blue: 0x24 / 255.0)
let islandSecondaryText = Color.white.opacity(0.64)

private let islandDropTypes: [UTType] = [.fileURL, .utf8PlainText, .plainText]

struct CompactLeadingView: View {
    let controller: IslandController
    @ObservedObject var timeline: IslandTimeline

    init(controller: IslandController) {
        self.controller = controller
        self._timeline = ObservedObject(wrappedValue: controller.timeline)
    }

    @ViewBuilder
    var body: some View {
        content
            .onDrop(of: islandDropTypes, delegate: IslandDropDelegate(controller: controller))
    }

    @ViewBuilder
    private var content: some View {
        switch timeline.compactState {
        case .inClass, .upcomingSoon:
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
}

struct CompactTrailingView: View {
    let controller: IslandController
    @ObservedObject var timeline: IslandTimeline

    init(controller: IslandController) {
        self.controller = controller
        self._timeline = ObservedObject(wrappedValue: controller.timeline)
    }

    @ViewBuilder
    var body: some View {
        content
            .onDrop(of: islandDropTypes, delegate: IslandDropDelegate(controller: controller))
    }

    @ViewBuilder
    private var content: some View {
        switch timeline.compactState {
        case .inClass(let remaining, let progress, _):
            HStack(spacing: 5) {
                Text("剩\(remaining)分")
                    .font(.system(size: 12, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(.white)

                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.white.opacity(0.22))
                        .frame(width: 24, height: 4)
                    RoundedRectangle(cornerRadius: 2)
                        .fill(islandCourseColor)
                        .frame(width: CGFloat(max(2.0, 24.0 * progress)), height: 4)
                }
            }
        case .upcomingSoon(let minutes, _):
            Text("\(minutes)分")
                .font(.system(size: 12, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(islandCourseColor)
        case .upcomingLater(let startText, _):
            Text(startText)
                .font(.system(size: 12, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(islandSecondaryText)
        case .todosOnly(let count):
            Text("\(count)")
                .font(.system(size: 12, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(islandTodoColor)
        case .hidden:
            EmptyView()
        }
    }
}
