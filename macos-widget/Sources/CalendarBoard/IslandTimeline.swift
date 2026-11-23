import Combine
import Foundation

struct TimelineClass: Codable, Equatable {
    let title: String
    let start: Int
    let end: Int?
    let place: String?
}

struct TimelinePayload: Codable, Equatable {
    let date: String
    let classes: [TimelineClass]
    let openTodos: Int
    let pending: Int
    let reviewAt: Int?
}

enum CompactDisplayState: Equatable {
    case inClass(remainingMinutes: Int, progress: Double, title: String)
    case upcomingSoon(inMinutes: Int, title: String)
    case upcomingLater(startTimeText: String, title: String)
    case todosOnly(count: Int)
    case hidden
}

@MainActor
final class IslandTimeline: ObservableObject {
    @Published private(set) var compactState: CompactDisplayState = .hidden
    @Published private(set) var currentPayload: TimelinePayload?

    var onCompactStateChanged: ((CompactDisplayState) -> Void)?
    var onMidnightRollover: (() -> Void)?
    var onPeriodicSync: (() -> Void)?
    var onPromptRequested: ((_ text: String, _ targetSurface: String) -> Void)?

    private var halfMinuteTimer: Timer?
    private var minuteBoundaryTimer: Timer?
    private var periodicSyncTimer: Timer?
    private var lastDateString: String
    private var lastCheckedMinute = -1
    private let defaults = UserDefaults.standard

    init() {
        lastDateString = Self.dateString(for: Date())
    }

    func update(payload: TimelinePayload) {
        currentPayload = payload
        recompute(now: Date())
    }

    func startTimers() {
        stopTimers()

        let halfMinuteTimer = Timer(timeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.recompute(now: Date())
            }
        }
        RunLoop.main.add(halfMinuteTimer, forMode: .common)
        self.halfMinuteTimer = halfMinuteTimer

        let now = Date()
        let nextMinute = Date(timeIntervalSince1970: (floor(now.timeIntervalSince1970 / 60.0) + 1.0) * 60.0)
        let minuteBoundaryTimer = Timer(fire: nextMinute, interval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.recompute(now: Date())
            }
        }
        RunLoop.main.add(minuteBoundaryTimer, forMode: .common)
        self.minuteBoundaryTimer = minuteBoundaryTimer

        let periodicSyncTimer = Timer(timeInterval: 300, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.onPeriodicSync?()
            }
        }
        RunLoop.main.add(periodicSyncTimer, forMode: .common)
        self.periodicSyncTimer = periodicSyncTimer
    }

    func stopTimers() {
        halfMinuteTimer?.invalidate()
        halfMinuteTimer = nil
        minuteBoundaryTimer?.invalidate()
        minuteBoundaryTimer = nil
        periodicSyncTimer?.invalidate()
        periodicSyncTimer = nil
    }

    func recompute(now: Date) {
        let calendar = Calendar.current
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: now)
        let todayStr = String(
            format: "%04d-%02d-%02d",
            components.year ?? 0,
            components.month ?? 0,
            components.day ?? 0
        )
        let nowMin = (components.hour ?? 0) * 60 + (components.minute ?? 0)

        if todayStr != lastDateString {
            lastDateString = todayStr
            onMidnightRollover?()
        }

        guard let payload = currentPayload else {
            setCompactState(.hidden)
            return
        }

        if payload.date == todayStr && nowMin != lastCheckedMinute {
            lastCheckedMinute = nowMin
            checkPrompts(payload: payload, todayStr: todayStr, nowMin: nowMin)
        }

        guard payload.date == todayStr else {
            setCompactState(.hidden)
            return
        }

        let sortedClasses = payload.classes.sorted { $0.start < $1.start }

        if let current = sortedClasses.first(where: { item in
            guard let end = item.end else { return false }
            return item.start <= nowMin && nowMin < end
        }), let end = current.end {
            let remaining = max(1, end - nowMin)
            let progress = min(
                1.0,
                max(0.0, Double(nowMin - current.start) / Double(max(1, end - current.start)))
            )
            setCompactState(.inClass(
                remainingMinutes: remaining,
                progress: progress,
                title: current.title
            ))
            return
        }

        if let next = sortedClasses.first(where: { $0.start > nowMin }) {
            let minutesUntilStart = next.start - nowMin
            if minutesUntilStart <= 60 {
                setCompactState(.upcomingSoon(inMinutes: minutesUntilStart, title: next.title))
            } else {
                setCompactState(.upcomingLater(
                    startTimeText: Self.formatMinutes(next.start),
                    title: next.title
                ))
            }
            return
        }

        let unfinished = payload.openTodos + payload.pending
        if unfinished > 0 {
            setCompactState(.todosOnly(count: unfinished))
        } else {
            setCompactState(.hidden)
        }
    }

    private func checkPrompts(payload: TimelinePayload, todayStr: String, nowMin: Int) {
        let totalUnfinished = max(0, payload.openTodos) + max(0, payload.pending)
        let reviewAt = payload.reviewAt ?? 1320

        if nowMin == 720 {
            if totalUnfinished > 0,
               defaults.string(forKey: "cb_prompt_12_date") != todayStr {
                defaults.set(todayStr, forKey: "cb_prompt_12_date")
                onPromptRequested?("还有 \(totalUnfinished) 项待办", "today")
            }
        }

        if nowMin == 1080 {
            if reviewAt == 1080,
               totalUnfinished > 0,
               defaults.string(forKey: "cb_last_review_prompt_date") != todayStr {
                defaults.set(todayStr, forKey: "cb_last_review_prompt_date")
                defaults.set(todayStr, forKey: "cb_prompt_18_date")
                onPromptRequested?("\(totalUnfinished) 项待决定", "review")
            } else if totalUnfinished > 0,
                      defaults.string(forKey: "cb_prompt_18_date") != todayStr {
                defaults.set(todayStr, forKey: "cb_prompt_18_date")
                onPromptRequested?("还有 \(totalUnfinished) 项待办", "today")
            }
        }

        if nowMin == reviewAt && reviewAt != 1080 {
            if totalUnfinished > 0,
               defaults.string(forKey: "cb_last_review_prompt_date") != todayStr {
                defaults.set(todayStr, forKey: "cb_last_review_prompt_date")
                onPromptRequested?("\(totalUnfinished) 项待决定", "review")
            }
        }
    }

    private func setCompactState(_ state: CompactDisplayState) {
        guard state != compactState else { return }
        compactState = state
        onCompactStateChanged?(state)
    }

    private static func dateString(for date: Date) -> String {
        let components = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(
            format: "%04d-%02d-%02d",
            components.year ?? 0,
            components.month ?? 0,
            components.day ?? 0
        )
    }

    private static func formatMinutes(_ minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }
}
