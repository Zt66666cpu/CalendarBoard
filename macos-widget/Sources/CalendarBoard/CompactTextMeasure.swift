import AppKit

/// 使用与 CompactViews 相同的等宽数字系统字体测量胶囊文字真实宽度。
enum CompactTextMeasure {
    private static let font = NSFont.monospacedDigitSystemFont(ofSize: 12, weight: .medium)

    /// 与 CompactViews.swift 中 compactFullTextView 显示的文字保持一致
    static func fullText(for state: CompactDisplayState) -> String {
        switch state {
        case .inClass(let remaining, _, _): return "剩\(remaining)分"
        case .upcomingSoon(let minutes, _): return "\(minutes)分"
        case .upcomingLater(let startText, _): return startText
        case .todosOnly(let count): return "\(count)"
        case .hidden: return ""
        }
    }

    static func fullTextWidth(for state: CompactDisplayState) -> CGFloat {
        let text = fullText(for: state)
        guard !text.isEmpty else { return 0 }
        return ceil((text as NSString).size(withAttributes: [.font: font]).width) + 1
    }
}
