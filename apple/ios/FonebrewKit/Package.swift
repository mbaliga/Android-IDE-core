// swift-tools-version:5.9
// Pure-Swift presentation helpers (no UIKit/SwiftUI) so `swift test` can run them on any host,
// including a Linux Swift toolchain. The app target depends on this package.
import PackageDescription

let package = Package(
    name: "FonebrewKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "FonebrewKit", targets: ["FonebrewKit"])],
    targets: [
        .target(name: "FonebrewKit"),
        .testTarget(name: "FonebrewKitTests", dependencies: ["FonebrewKit"]),
    ]
)
