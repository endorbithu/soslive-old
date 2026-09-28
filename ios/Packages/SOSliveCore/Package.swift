// swift-tools-version:5.9
import PackageDescription

// Platform independent core of the iOS app: API models, HTTP client with token refresh,
// services and validation. No UIKit/SwiftUI, so it is unit tested with `swift test`.
let package = Package(
    name: "SOSliveCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "SOSliveCore", targets: ["SOSliveCore"]),
    ],
    targets: [
        .target(name: "SOSliveCore"),
        .testTarget(name: "SOSliveCoreTests", dependencies: ["SOSliveCore"]),
    ]
)
