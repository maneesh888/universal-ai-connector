// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "UniversalAiConnectorPOC",
    platforms: [
        .iOS(.v17),
    ],
    products: [
        .library(
            name: "UniversalAiConnectorPOC",
            targets: ["UniversalAiConnectorPOC"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "UniversalAiConnectorBridge",
            path: "Artifacts/UniversalAiConnectorBridge.xcframework"
        ),
        .target(
            name: "UniversalAiConnectorPOC",
            dependencies: ["UniversalAiConnectorBridge"]
        ),
        .testTarget(
            name: "UniversalAiConnectorPOCTests",
            dependencies: ["UniversalAiConnectorPOC"]
        ),
    ]
)
