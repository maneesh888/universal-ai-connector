// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "UniversalAiConnector",
    platforms: [
        .iOS(.v17),
    ],
    products: [
        .library(
            name: "UniversalAiConnector",
            targets: ["UniversalAiConnector"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "UniversalAiConnectorBridge",
            path: "Artifacts/UniversalAiConnectorBridge.xcframework"
        ),
        .target(
            name: "UniversalAiConnector",
            dependencies: ["UniversalAiConnectorBridge"]
        ),
        .testTarget(
            name: "UniversalAiConnectorTests",
            dependencies: ["UniversalAiConnector"]
        ),
    ]
)
