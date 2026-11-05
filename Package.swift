// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "UniversalAiConnector",
    platforms: [.iOS(.v17)],
    products: [.library(name: "UniversalAiConnector", targets: ["UniversalAiConnector"])],
    targets: [
        .binaryTarget(name: "UniversalAiConnectorBridge", url: "https://github.com/maneesh888/universal-ai-connector/releases/download/v0.1.0-alpha.0.p8.1/UniversalAiConnectorBridge.xcframework.zip", checksum: "e4793423ae09cf5b5a05e5f5bec2ee535f735addee450f4d64615d929c8de542"),
        .target(name: "UniversalAiConnector", dependencies: ["UniversalAiConnectorBridge"], path: "swift-package/Sources/UniversalAiConnector"),
        .testTarget(name: "UniversalAiConnectorTests", dependencies: ["UniversalAiConnector", "UniversalAiConnectorBridge"], path: "swift-package/Tests/UniversalAiConnectorTests"),
    ]
)
