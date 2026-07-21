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
        .target(
            name: "UniversalAiConnector",
            dependencies: ["UniversalAiConnectorBridge"]
        ),
        .testTarget(
            name: "UniversalAiConnectorPOCTests",
            dependencies: ["UniversalAiConnectorPOC"]
        ),
        .testTarget(
            name: "UniversalAiConnectorTests",
            dependencies: ["UniversalAiConnector"]
        ),
    ]
)
