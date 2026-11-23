// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CalendarBoard",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(path: "Vendor/DynamicNotchKit"),
        .package(url: "https://github.com/sindresorhus/KeyboardShortcuts", exact: "1.9.4")
    ],
    targets: [
        .executableTarget(
            name: "CalendarBoard",
            dependencies: [
                "DynamicNotchKit",
                "KeyboardShortcuts"
            ],
            path: "Sources/CalendarBoard",
            swiftSettings: [
                .swiftLanguageMode(.v5)
            ]
        )
    ]
)
