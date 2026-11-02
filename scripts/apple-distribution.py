#!/usr/bin/env python3
"""Unsigned iOS archive, manifest, API and standalone-consumer proof. No publication."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import plistlib
import re
import shutil
import stat
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
NAME = "UniversalAiConnectorBridge"
PRODUCT = "UniversalAiConnector"
REPOSITORY = "https://github.com/maneesh888/universal-ai-connector"
spec = importlib.util.spec_from_file_location("apple_properties", ROOT / "scripts/maven-inventory.py")
metadata_reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata_reader)


class AppleError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise AppleError(message)


def environment():
    prefixes = ("OPENAI_", "ANTHROPIC_", "OPENROUTER_", "GATEWAY_", "UAC_", "ORG_GRADLE_PROJECT_signing", "ORG_GRADLE_PROJECT_mavenCentral")
    return {k: v for k, v in os.environ.items() if not k.startswith(prefixes) and k not in {"GH_DEBUG", "GIT_TRACE", "GIT_CURL_VERBOSE"}}


def configuration(root):
    props = metadata_reader.properties(root / "gradle.properties")
    release = metadata_reader.properties(root / "distribution/release.properties")
    version = props["VERSION_NAME"]
    require(re.fullmatch(r"0\.1\.0-alpha\.0\.p8\.[1-9][0-9]*", version) and release["RELEASE_SCOPE"] == "libraries-first" and release["APPLE_SIGNING_POLICY"] == "unsigned-proof", "Unapproved Apple release identity or signing scope.")
    require(release["POM_URL"] == REPOSITORY and release["APPLE_ASSET_NAME"] == NAME + ".xcframework.zip", "Apple asset destination differs.")
    return {"version": version, "tag": "v" + version, "asset": release["APPLE_ASSET_NAME"], "url": REPOSITORY + "/releases/download/v" + version + "/" + release["APPLE_ASSET_NAME"], "signing": "unsigned", "debugSymbols": "not included"}


def manifest_text(config, checksum, remote=True):
    require(re.fullmatch(r"[0-9a-f]{64}", checksum), "Invalid SwiftPM checksum.")
    binary = ('url: "' + config["url"] + '", checksum: "' + checksum + '"') if remote else 'path: "Artifacts/' + NAME + '.xcframework"'
    return '''// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "UniversalAiConnector",
    platforms: [.iOS(.v17)],
    products: [.library(name: "UniversalAiConnector", targets: ["UniversalAiConnector"])],
    targets: [
        .binaryTarget(name: "UniversalAiConnectorBridge", ''' + binary + '''),
        .target(name: "UniversalAiConnector", dependencies: ["UniversalAiConnectorBridge"], path: "swift-package/Sources/UniversalAiConnector"),
        .testTarget(name: "UniversalAiConnectorTests", dependencies: ["UniversalAiConnector", "UniversalAiConnectorBridge"], path: "swift-package/Tests/UniversalAiConnectorTests"),
    ]
)
'''


def file_hashes(path):
    require(path.is_dir() and not path.is_symlink(), "Missing or linked source tree.")
    result = {}
    for item in sorted(path.rglob("*")):
        require(not item.is_symlink(), "Linked package input.")
        if item.is_file():
            result[item.relative_to(path).as_posix()] = hashlib.sha256(item.read_bytes()).hexdigest()
    return result


def framework_metadata(root):
    require(root.is_dir() and not root.is_symlink(), "Missing XCFramework.")
    info = plistlib.loads((root / "Info.plist").read_bytes())
    libraries = info.get("AvailableLibraries", [])
    require(len(libraries) == 2 and {v["LibraryIdentifier"] for v in libraries} == {"ios-arm64", "ios-arm64-simulator"}, "Unexpected Apple slices.")
    for library in libraries:
        identifier = library["LibraryIdentifier"]
        require(library["LibraryPath"] == NAME + ".framework" and library["SupportedPlatform"] == "ios" and library["SupportedArchitectures"] == ["arm64"], "Unexpected framework platform or architecture.")
        require(library.get("SupportedPlatformVariant") == ("simulator" if identifier.endswith("simulator") else None), "Unexpected Apple platform variant.")
        framework = root / identifier / (NAME + ".framework")
        require(plistlib.loads((framework / "Info.plist").read_bytes()).get("MinimumOSVersion") == "17.0", "Unexpected framework minimum OS.")
        require((framework / NAME).is_file() and (framework / "Headers" / (NAME + ".h")).is_file(), "Missing framework binary or header.")


def normalized_archive(source, destination):
    framework_metadata(source)
    files = file_hashes(source)
    require(files and not any(".dSYM/" in n or "_CodeSignature/" in n or n.endswith(".DS_Store") for n in files), "Unexpected debug-symbol, signature or desktop metadata input.")
    require(not destination.exists() and not destination.is_symlink(), "Archive output already exists.")
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(files):
            body = (source / name).read_bytes()
            require(hashlib.sha256(body).hexdigest() == files[name], "Framework changed while archiving.")
            if name.endswith("Info.plist"):
                value = plistlib.loads(body)
                if name == "Info.plist":
                    # xcodebuild may emit equivalent library records in completion order.
                    value["AvailableLibraries"] = sorted(value["AvailableLibraries"], key=lambda library: library["LibraryIdentifier"])
                body = plistlib.dumps(value, fmt=plistlib.FMT_XML, sort_keys=True)
            info = zipfile.ZipInfo(NAME + ".xcframework/" + name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, body, compresslevel=9)
    return hashlib.sha256(destination.read_bytes()).hexdigest()


def extract_archive(archive_path, destination):
    require(not destination.exists() and not destination.is_symlink(), "Extraction output must be fresh.")
    destination.mkdir(mode=0o700)
    with zipfile.ZipFile(archive_path) as archive:
        infos = archive.infolist()
        require(len(infos) < 4096 and len(infos) == len({i.filename for i in infos}) and len(infos) == len({i.filename.casefold() for i in infos}) and sum(i.file_size for i in infos) < 256 * 1024 * 1024, "Unsafe Apple archive inventory.")
        for info in infos:
            path = PurePosixPath(info.filename)
            require(len(path.parts) > 1 and not path.is_absolute() and ".." not in path.parts and "\\" not in info.filename and path.parts[0] == NAME + ".xcframework" and stat.S_ISREG(info.external_attr >> 16), "Unsafe Apple archive entry.")
            require(".dSYM/" not in info.filename and "_CodeSignature/" not in info.filename, "Unexpected signed/debug-symbol archive.")
        for info in infos:
            target = destination / info.filename
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(archive.read(info))
    framework = destination / (NAME + ".xcframework")
    framework_metadata(framework)
    return framework


def public_api(graphs):
    symbols, relationships = {}, {}
    for graph in graphs:
        require(graph["module"]["name"] == PRODUCT, "Unexpected Swift API module.")
        for symbol in graph["symbols"]:
            if symbol.get("accessLevel") not in {"public", "open"}:
                continue
            identifier = symbol["identifier"]["precise"]
            declaration = "".join(f["spelling"] for f in symbol.get("declarationFragments", []))
            require(not re.search(r"Kotlin|Ktor|UACB|UniversalAiConnectorBridge|CoroutineScope|HttpClient", declaration), "Implementation type leaked into the public Swift API.")
            entry = {"kind": symbol["kind"]["identifier"], "declaration": declaration, "generics": symbol.get("swiftGenerics", {}), "availability": sorted(symbol.get("availability", []), key=lambda value: json.dumps(value, sort_keys=True))}
            require(identifier not in symbols or symbols[identifier] == entry, "Conflicting public Swift symbol.")
            symbols[identifier] = entry
        for relation in graph.get("relationships", []):
            value = {k: relation[k] for k in ["kind", "source", "target"]} | {"constraints": relation.get("swiftConstraints", [])}
            relationships[json.dumps(value, sort_keys=True)] = value
    require(symbols, "Empty public Swift API.")
    return {"module": PRODUCT, "symbols": dict(sorted(symbols.items())), "relationships": [relationships[k] for k in sorted(relationships) if relationships[k]["source"] in symbols]}


def run(root, output, name, command):
    print("Apple check: " + name, flush=True)
    log = output / (name + ".log")
    descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        result = subprocess.run(command, cwd=root, env=environment(), stdout=stream, stderr=subprocess.STDOUT, timeout=1800)
    require(result.returncode == 0, name + " failed; inspect private generated diagnostics.")


def manifest_parity(root):
    values = []
    for package in [root, root / "swift-package"]:
        raw = subprocess.check_output(["swift", "package", "dump-package", "--package-path", str(package)], env=environment(), stderr=subprocess.DEVNULL, timeout=30)
        value = json.loads(raw)
        targets = []
        for target in value["targets"]:
            if target["type"] != "binary":
                directory = "Tests" if target["type"] == "test" else "Sources"
                path = target.get("path") or directory + "/" + target["name"]
                require((package / path).resolve() == (root / "swift-package" / directory / target["name"]).resolve(), "Local/remote wrapper source paths differ.")
            targets.append({k: v for k, v in target.items() if k not in {"path", "url", "checksum"}})
        values.append({k: value[k] for k in ["name", "products", "platforms", "toolsVersion"]} | {"targets": targets})
    require(values[0] == values[1], "Local and remote Swift package products, platforms or targets differ.")


def project(root, package, version, remote):
    """A tiny host forces a public-product device executable link, without install/signing."""
    names = ["project", "main", "products", "target", "app", "source", "sourceBuild", "sources", "frameworks", "productBuild", "package", "product", "projectConfigs", "targetConfigs", "projectDebug", "projectRelease", "targetDebug", "targetRelease"]
    ids = {name: f"{i + 1:024X}" for i, name in enumerate(names)}
    objects = {}
    def add(key, isa, **fields):
        objects[ids[key]] = {"isa": isa, **fields}
    add("main", "PBXGroup", children=[ids["source"], ids["products"]], sourceTree="<group>")
    add("products", "PBXGroup", children=[ids["app"]], name="Products", sourceTree="<group>")
    add("source", "PBXFileReference", lastKnownFileType="sourcecode.swift", path="ConsumerApp.swift", sourceTree="<group>")
    add("app", "PBXFileReference", explicitFileType="wrapper.application", includeInIndex=0, path="ReleaseConsumerApp.app", sourceTree="BUILT_PRODUCTS_DIR")
    add("sourceBuild", "PBXBuildFile", fileRef=ids["source"])
    add("productBuild", "PBXBuildFile", productRef=ids["product"])
    add("sources", "PBXSourcesBuildPhase", buildActionMask=2147483647, files=[ids["sourceBuild"]], runOnlyForDeploymentPostprocessing=0)
    add("frameworks", "PBXFrameworksBuildPhase", buildActionMask=2147483647, files=[ids["productBuild"]], runOnlyForDeploymentPostprocessing=0)
    if remote:
        add("package", "XCRemoteSwiftPackageReference", repositoryURL=REPOSITORY, requirement={"kind": "exactVersion", "version": version})
    else:
        add("package", "XCLocalSwiftPackageReference", relativePath=os.path.relpath(package, root))
    add("product", "XCSwiftPackageProductDependency", package=ids["package"], productName=PRODUCT)
    add("target", "PBXNativeTarget", buildConfigurationList=ids["targetConfigs"], buildPhases=[ids["sources"], ids["frameworks"]], buildRules=[], dependencies=[], name="ReleaseConsumerApp", productName="ReleaseConsumerApp", productReference=ids["app"], productType="com.apple.product-type.application", packageProductDependencies=[ids["product"]])
    for prefix in ["project", "target"]:
        for mode in ["Debug", "Release"]:
            settings = {"IPHONEOS_DEPLOYMENT_TARGET": "17.0", "SWIFT_VERSION": "6.0", "SDKROOT": "iphoneos", "CODE_SIGNING_ALLOWED": "NO", "CODE_SIGNING_REQUIRED": "NO", "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator", "SWIFT_OPTIMIZATION_LEVEL": "-Onone" if mode == "Debug" else "-O"}
            if prefix == "target":
                settings.update(PRODUCT_BUNDLE_IDENTIFIER="com.myadidi.universalai.releaseconsumer", PRODUCT_NAME="$(TARGET_NAME)", GENERATE_INFOPLIST_FILE="YES", INFOPLIST_KEY_UILaunchScreen_Generation="YES", TARGETED_DEVICE_FAMILY="1,2")
            add(prefix + mode, "XCBuildConfiguration", buildSettings=settings, name=mode)
        add(prefix + "Configs", "XCConfigurationList", buildConfigurations=[ids[prefix + "Debug"], ids[prefix + "Release"]], defaultConfigurationIsVisible=0, defaultConfigurationName="Debug")
    add("project", "PBXProject", attributes={"LastUpgradeCheck": "1640"}, buildConfigurationList=ids["projectConfigs"], compatibilityVersion="Xcode 14.0", developmentRegion="en", knownRegions=["en", "Base"], mainGroup=ids["main"], productRefGroup=ids["products"], projectDirPath="", projectRoot="", targets=[ids["target"]], packageReferences=[ids["package"]])
    directory = root / "ReleaseConsumerApp.xcodeproj"
    directory.mkdir()
    (directory / "project.pbxproj").write_bytes(plistlib.dumps({"archiveVersion": "1", "classes": {}, "objectVersion": "56", "objects": objects, "rootObject": ids["project"]}))
    scheme = ET.Element("Scheme", LastUpgradeVersion="1640", version="1.7")
    build = ET.SubElement(scheme, "BuildAction", parallelizeBuildables="YES", buildImplicitDependencies="YES")
    entries = ET.SubElement(build, "BuildActionEntries")
    entry = ET.SubElement(entries, "BuildActionEntry", buildForTesting="YES", buildForRunning="YES", buildForProfiling="YES", buildForArchiving="YES", buildForAnalyzing="YES")
    ET.SubElement(entry, "BuildableReference", BuildableIdentifier="primary", BlueprintIdentifier=ids["target"], BuildableName="ReleaseConsumerApp.app", BlueprintName="ReleaseConsumerApp", ReferencedContainer="container:ReleaseConsumerApp.xcodeproj")
    schemes = directory / "xcshareddata/xcschemes"
    schemes.mkdir(parents=True)
    (schemes / "ReleaseConsumerApp.xcscheme").write_bytes(ET.tostring(scheme, encoding="utf-8", xml_declaration=True))
    return directory


def verify_notices(root, output, framework, resolved=None):
    command = ["python3", "-I", "-B", str(root / "scripts/native-notices.py"), "--destination", str(framework), "--verify"]
    command += ["--resolved", str(resolved)] if resolved is not None else ["--archive-verify"]
    run(root, output, "extracted-notices" if resolved is not None else "downloaded-notices", command)


def consumer(root, output, config, archive, remote=False, expected_revision=None):
    directory = output / ("remote-consumer" if remote else "local-consumer")
    if directory.exists():
        require(not directory.is_symlink(), "Linked consumer output.")
        shutil.rmtree(directory)
    directory.mkdir(mode=0o700)
    package = directory / "package"
    if not remote:
        package.mkdir()
        framework = extract_archive(archive, package / "Artifacts")
        verify_notices(root, output, framework, root / "bridge/build/distribution/native-notice-inputs.tsv")
        shutil.copytree(root / "swift-package/Sources", package / "swift-package/Sources")
        shutil.copytree(root / "swift-package/Tests", package / "swift-package/Tests")
        (package / "Package.swift").write_text(manifest_text(config, config["sha256"], remote=False))
    fixture = directory / "fixture"
    shutil.copytree(root / "distribution/consumers/swift/Sources", fixture / "Sources")
    shutil.copytree(root / "distribution/consumers/swift/Tests", fixture / "Tests")
    (fixture / "Tests/ReleaseConsumerTests/ExpectedRelease.swift").write_text('enum ExpectedRelease { static let version = "' + config["version"] + '" }\n')
    dependency = '.package(url: "' + REPOSITORY + '", exact: "' + config["version"] + '")' if remote else '.package(path: "../package")'
    identity = "universal-ai-connector" if remote else "package"
    template = (root / "distribution/consumers/swift/Package.swift.in").read_text()
    (fixture / "Package.swift").write_text(template.replace("@@DEPENDENCY@@", dependency).replace("@@IDENTITY@@", identity))
    destination = subprocess.check_output([str(root / "scripts/simulator-destination.sh")], env=environment(), text=True, timeout=120).strip()
    derived = directory / "simulator-derived"
    result_bundle = directory / "consumer.xcresult"
    run(fixture, output, "remote-simulator" if remote else "local-simulator", ["xcodebuild", "test", "-scheme", "ReleaseConsumer", "-destination", destination, "-derivedDataPath", str(derived), "-resultBundlePath", str(result_bundle), "-clonedSourcePackagesDirPath", str(directory / "source-packages"), "CODE_SIGN_IDENTITY=", "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO"])
    summary = json.loads(subprocess.check_output(["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(result_bundle), "--compact"], env=environment(), stderr=subprocess.DEVNULL, timeout=60))
    tests = test_summary(summary)
    host = directory / "host"
    host.mkdir()
    shutil.copyfile(root / "distribution/consumers/swift/Host/ConsumerApp.swift", host / "ConsumerApp.swift")
    app = project(host, package, config["version"], remote)
    device = directory / "device-derived"
    run(root, output, "remote-device-link" if remote else "local-device-link", ["xcodebuild", "build", "-project", str(app), "-scheme", "ReleaseConsumerApp", "-configuration", "Debug", "-destination", "generic/platform=iOS", "-derivedDataPath", str(device), "-clonedSourcePackagesDirPath", str(directory / "host-source-packages"), "CODE_SIGN_IDENTITY=", "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO"])
    require((device / "Build/Products/Debug-iphoneos/ReleaseConsumerApp.app/ReleaseConsumerApp").is_file(), "Generic-device executable was not linked.")
    if remote:
        groups = [[fixture / "Package.resolved", fixture / ".swiftpm/xcode/package.xcworkspace/xcshareddata/swiftpm/Package.resolved"], [app / "project.xcworkspace/xcshareddata/swiftpm/Package.resolved"]]
        for candidates in groups:
            resolved = [p for p in candidates if p.is_file()]
            require(resolved, "Remote consumer resolution record is missing.")
            for path in resolved:
                verify_pins(json.loads(path.read_text()), config["version"], expected_revision)
    return {"derived": derived, "tests": tests, "deviceExecutableSha256": hashlib.sha256((device / "Build/Products/Debug-iphoneos/ReleaseConsumerApp.app/ReleaseConsumerApp").read_bytes()).hexdigest()}


def api_check(root, output, derived, create=False):
    graph_directory = output / "symbol-graphs"
    if graph_directory.exists():
        require(not graph_directory.is_symlink(), "Linked API output.")
        shutil.rmtree(graph_directory)
    graph_directory.mkdir()
    products = derived / "Build/Products/Debug-iphonesimulator"
    sdk = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"], env=environment(), text=True, timeout=30).strip()
    run(root, output, "swift-api", ["swift", "symbolgraph-extract", "-module-name", PRODUCT, "-minimum-access-level", "public", "-skip-synthesized-members", "-skip-inherited-docs", "-target", "arm64-apple-ios17.0-simulator", "-sdk", sdk, "-I", str(products), "-F", str(products), "-output-dir", str(graph_directory)])
    actual = public_api([json.loads(p.read_text()) for p in sorted(graph_directory.glob("*.symbols.json"))])
    baseline = root / "swift-package/api/public-api.json"
    if create and not baseline.exists():
        baseline.parent.mkdir(exist_ok=True)
        baseline.write_text(json.dumps(actual, indent=2) + "\n")
    require(baseline.is_file() and not baseline.is_symlink(), "Missing or linked supported Swift API baseline.")
    verify_api(json.loads(baseline.read_text()), actual, output)
    return len(actual["symbols"])


def verify_api(expected, actual, output):
    """Retain public API evidence only; never relax the exact compatibility assertion."""
    for name in ["swift-api-comparison.json", "swift-public-api-actual.json"]:
        require(not (output / name).is_symlink(), "Linked API comparison output.")
        (output / name).unlink(missing_ok=True)
    if expected == actual:
        return
    old, new = expected["symbols"], actual["symbols"]
    report = {
        "expectedSymbols": len(old), "actualSymbols": len(new),
        "addedSymbols": sorted(new.keys() - old.keys()),
        "removedSymbols": sorted(old.keys() - new.keys()),
        "changedSymbols": sorted(k for k in old.keys() & new.keys() if old[k] != new[k]),
        "relationshipsChanged": expected["relationships"] != actual["relationships"],
        "assertion": "exact reviewed public API equality remains required",
    }
    for name, value in [("swift-api-comparison.json", report), ("swift-public-api-actual.json", actual)]:
        descriptor = os.open(output / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write(json.dumps(value, indent=2) + "\n")
    raise AppleError("Supported Swift API differs from its reviewed baseline; inspect generated public API comparison evidence.")


def verify_pins(record, version, revision):
    pins = [p for p in record["pins"] if p["identity"] == "universal-ai-connector"]
    require(len(pins) == 1 and pins[0]["state"]["version"] == version and pins[0]["state"]["revision"] == revision, "Remote consumer did not resolve the exact approved tag/source.")


def test_summary(record):
    expected = {"totalTestCount": 1, "passedTests": 1, "failedTests": 0, "skippedTests": 0, "expectedFailures": 0}
    require(record.get("result") == "Passed" and all(type(record.get(k)) is int and record[k] == v for k, v in expected.items()), "Consumer did not execute exactly one successful, unskipped runtime test.")
    return expected


def remote_source(config):
    command = ["gh", "api", "repos/maneesh888/universal-ai-connector/git/ref/tags/" + config["tag"]]
    reference = json.loads(subprocess.check_output(command, env=environment(), stderr=subprocess.DEVNULL, timeout=30))["object"]
    for _ in range(3):
        require(re.fullmatch(r"[0-9a-f]{40}", reference["sha"]), "Invalid remote tag identity.")
        if reference["type"] == "commit":
            return reference["sha"]
        require(reference["type"] == "tag", "Unexpected remote tag object.")
        reference = json.loads(subprocess.check_output(["gh", "api", "repos/maneesh888/universal-ai-connector/git/tags/" + reference["sha"]], env=environment(), stderr=subprocess.DEVNULL, timeout=30))["object"]
    raise AppleError("Excessive tag indirection.")


def declared_manifest(root, config):
    path = root / "distribution/apple-release.json"
    require(path.is_file() and not path.is_symlink(), "Missing Apple release record.")
    declared = json.loads(path.read_text())
    checksum = declared.get("sha256", "")
    require(declared == config | {"sha256": checksum} and re.fullmatch(r"[0-9a-f]{64}", checksum), "Apple release identity or checksum differs.")
    manifest = root / "Package.swift"
    require(manifest.is_file() and not manifest.is_symlink() and manifest.read_text() == manifest_text(config, checksum), "Root remote SwiftPM manifest drift.")
    return declared


def archive_binding(config, frozen, mode):
    require(mode in {"build", "local", "remote", "prepare"}, "Invalid archive proof mode.")
    require({k: v for k, v in config.items() if k != "sha256"} == {k: v for k, v in frozen.items() if k != "sha256"}, "Archive release identity differs.")
    matches = config["sha256"] == frozen["sha256"]
    require(mode == "build" or matches, "Root Swift checksum differs from the produced archive.")
    return matches


def publication_ready(root, config):
    frozen = declared_manifest(root, config)
    output = root / "bridge/build/distribution/apple"
    receipt_path = output / "verification.json"
    require(receipt_path.is_file() and not receipt_path.is_symlink(), "Missing strict publisher verification receipt.")
    receipt = json.loads(receipt_path.read_text())
    require(receipt.get("mode") == "local" and receipt.get("workingTreeDirty") is False and receipt.get("rebuildReproduced") is True and receipt.get("frozenChecksumMatches") is True, "Run strict local --rebuild-check on the clean accepted source first.")
    require(receipt.get("version") == config["version"] and receipt.get("signing") == "unsigned", "Publisher receipt version/signing policy differs.")
    test_summary(receipt["consumerTests"] | {"result": "Passed"})
    require(receipt.get("genericDeviceExecutableLinked") is True and type(receipt.get("publicSwiftSymbols")) is int and receipt["publicSwiftSymbols"] > 0 and receipt.get("sha256") == frozen["sha256"], "Incomplete publisher consumer/API proof.")
    archive = output / config["asset"]
    require(archive.is_file() and not archive.is_symlink() and hashlib.sha256(archive.read_bytes()).hexdigest() == frozen["sha256"], "Publication archive differs from the verified bytes.")
    def git(*arguments):
        return subprocess.check_output(["git", *arguments], cwd=root, env=environment(), stderr=subprocess.DEVNULL, text=True, timeout=30).strip()
    require(git("rev-parse", "HEAD") == receipt["head"] and git("rev-parse", "HEAD^{tree}") == receipt["tree"] and not git("status", "--porcelain"), "Publication source must remain the clean verified commit.")
    subprocess.run(["git", "merge-base", "--is-ancestor", receipt["head"], "origin/main"], cwd=root, env=environment(), check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    runs = json.loads(subprocess.check_output(["gh", "run", "list", "--repo", "maneesh888/universal-ai-connector", "--branch", "main", "--commit", receipt["head"], "--workflow", "ci.yml", "--json", "headSha,event,status,conclusion"], cwd=root, env=environment(), stderr=subprocess.DEVNULL, timeout=30))
    require(any(run["headSha"] == receipt["head"] and run["event"] == "push" and run["status"] == "completed" and run["conclusion"] == "success" for run in runs), "Exact accepted-main CI must succeed before Apple publication.")
    return {"target": "GitHub-hosted SwiftPM", "source": receipt["head"], "version": config["version"], "tag": config["tag"], "sha256": frozen["sha256"], "writesPerformed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["metadata", "prepare", "build", "local", "remote", "publication-ready"])
    parser.add_argument("--rebuild-check", action="store_true")
    args = parser.parse_args()
    config = configuration(ROOT)
    if args.mode == "metadata":
        declared_manifest(ROOT, config)
        print("Apple manifest identity is consistent; hosted availability is not asserted.")
        return
    if args.mode == "publication-ready":
        print(json.dumps(publication_ready(ROOT, config)))
        return
    require(sys.platform == "darwin", "Apple distribution execution requires a Mac.")
    require(args.mode != "remote" or not args.rebuild_check, "Remote consumption does not rebuild published bytes.")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT))
    output = ROOT / "bridge/build/distribution/apple"
    require(not output.is_symlink(), "Linked Apple output.")
    output.mkdir(mode=0o700, parents=True, exist_ok=True)
    receipt_path = output / ({"remote": "remote-verification.json", "build": "build-verification.json"}.get(args.mode, "verification.json"))
    receipt_path.unlink(missing_ok=True)
    archive = output / config["asset"]
    require(not archive.is_symlink(), "Linked archive output.")
    archive.unlink(missing_ok=True)
    revision = None
    if args.mode == "remote":
        require(not dirty, "Remote proof requires the clean accepted tag checkout.")
        revision = remote_source(config)
        require(revision == head, "Checkout does not match the published package tag.")
        declared = declared_manifest(ROOT, config)
        run(ROOT, output, "download-public-asset", ["gh", "release", "download", config["tag"], "--repo", "maneesh888/universal-ai-connector", "--pattern", config["asset"], "--dir", str(output)])
        config.update(sha256=hashlib.sha256(archive.read_bytes()).hexdigest())
        archive_binding(config, declared, "remote")
        inspect = output / "remote-asset-inspection"
        if inspect.exists():
            require(not inspect.is_symlink(), "Linked archive inspection output.")
            shutil.rmtree(inspect)
        framework = extract_archive(archive, inspect)
        verify_notices(ROOT, output, framework)
    else:
        run(ROOT, output, "release-xcframework", [str(ROOT / "scripts/build-xcframework.sh"), "--release"])
        config["sha256"] = normalized_archive(ROOT / ("swift-package/Artifacts/" + NAME + ".xcframework"), archive)
        checksum = subprocess.check_output(["swift", "package", "compute-checksum", str(archive)], env=environment(), text=True, timeout=30).strip()
        require(checksum == config["sha256"], "SwiftPM checksum differs.")
        if args.rebuild_check:
            run(ROOT, output, "rebuild-release-xcframework", [str(ROOT / "scripts/build-xcframework.sh"), "--release", "--rebuild"])
            repeated = output / "repeat.zip"
            require(not repeated.is_symlink(), "Linked reproducibility output.")
            repeated.unlink(missing_ok=True)
            require(normalized_archive(ROOT / ("swift-package/Artifacts/" + NAME + ".xcframework"), repeated) == config["sha256"], "A forced release rebuild changed the frozen archive.")
            repeated.unlink()
        if args.mode == "prepare":
            for path in [ROOT / "distribution/apple-release.json", ROOT / "Package.swift"]:
                require(not path.is_symlink(), "Linked source manifest output.")
            (ROOT / "distribution/apple-release.json").write_text(json.dumps(config, indent=2) + "\n")
            (ROOT / "Package.swift").write_text(manifest_text(config, config["sha256"]))
        frozen = declared_manifest(ROOT, {k: v for k, v in config.items() if k != "sha256"})
        archive_binding(config, frozen, args.mode)
    (output / (config["asset"] + ".sha256")).write_text(config["sha256"] + "  " + config["asset"] + "\n")
    manifest_parity(ROOT)
    proof = consumer(ROOT, output, config, archive, remote=args.mode == "remote", expected_revision=revision)
    symbols = api_check(ROOT, output, proof["derived"], create=args.mode == "prepare")
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() == head, "HEAD changed during Apple verification.")
    frozen_checksum = declared_manifest(ROOT, {k: v for k, v in config.items() if k != "sha256"})["sha256"]
    receipt = {"head": head, "tree": tree, "version": config["version"], "mode": args.mode, "workingTreeDirty": dirty or bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)), "sha256": config["sha256"], "frozenChecksumMatches": config["sha256"] == frozen_checksum, "archive": str(archive), "rebuildReproduced": args.rebuild_check, "consumerTests": proof["tests"], "genericDeviceExecutableSha256": proof["deviceExecutableSha256"], "genericDeviceExecutableLinked": True, "publicSwiftSymbols": symbols, "signing": "unsigned", "resolvedSource": revision, "hostedConsumptionVerified": args.mode == "remote", "publishedByCommand": False}
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))


if __name__ == "__main__":
    try:
        main()
    except AppleError as error:
        # AppleError messages are fixed internal validation/stage labels, never upstream text.
        print("Apple distribution validation failed: " + str(error) + " No publication was performed.", file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError):
        print("Apple distribution validation failed; inspect private generated diagnostics. No publication was performed.", file=sys.stderr)
        sys.exit(1)
