# Native distribution notices

These unchanged license texts and source attribution blocks accompany the statically linked
Kotlin/Native iOS XCFramework. The package's own license remains the root MIT `LICENSE`.
No dependency license is replaced or relicensed.

`manifest.json` records the 48 resolved Klib inputs (19 modules for each iOS target, including
cinterop artifacts), their SHA-256 hashes, the exact source-JAR URLs/hashes inspected, and hashes
and provenance of every retained notice. Source comment blocks are reproduced unchanged;
section labels identify their source and deduplicate identical blocks only within one archive.
This is an inventory of resolved inputs, not a claim that every input survives native linking.

The Kotlin 2.4.10 standard-library source archive and runtime sources were reviewed separately.
Harmony, Unicode (including the original database terms), ThreeTenBP, GWT, Guava, UTF8CPP and
Boost-derived attributions are retained alongside their original terms. The Kotlin compiler
NOTICE and libbacktrace notice are retained conservatively: the former describes the upstream
compiler distribution, not an assertion that the compiler is embedded; libbacktrace occurs in
native runtime inputs but final-binary inclusion was not established. Compiler tooling, tests,
and macOS-only Breakpad are not represented as redistributed iOS components.

The build validates the resolved native inputs against this reviewed set and copies this entire
notice directory plus the root MIT LICENSE into the XCFramework. Dependency or compiler changes
must update the inventory after a fresh notice review. The validator rejects missing or changed
notices and dependency drift. The release ZIP must retain these files unchanged.
