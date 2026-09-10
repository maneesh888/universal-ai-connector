// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "UniversalAiConnectorSampleSupport",
    platforms: [
        .iOS(.v17),
    ],
    products: [
        .library(
            name: "UniversalAiConnectorSampleSupport",
            targets: ["UniversalAiConnectorSampleSupport"]
        ),
    ],
    dependencies: [
        .package(path: "../../../swift-package"),
    ],
    targets: [
        .target(
            name: "UniversalAiConnectorSampleSupport",
            dependencies: [
                .product(
                    name: "UniversalAiConnector",
                    package: "swift-package"
                ),
            ],
            path: "Sources/UniversalAiConnectorSampleSupport"
        ),
        .testTarget(
            name: "UniversalAiConnectorSampleSupportTests",
            dependencies: ["UniversalAiConnectorSampleSupport"],
            path: "Tests/UniversalAiConnectorSampleSupportTests"
        ),
    ]
)
