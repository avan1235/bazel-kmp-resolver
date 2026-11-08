# The manifest

`bazel-kmp-resolver generate-bazel-manifest` resolves Maven coordinates for a set of platforms and writes a JSON
manifest from which a Bazel module extension generates the targets of every resolved library, one per platform behind a
single alias per library, without resolving anything itself. This document is the contract between the resolver and
that extension, planned in rules_kotlin: the commands, the schema of the manifest (version 2), the targets generated
from it, and the pinned-manifest workflow.

## Commands

```shell
bazel-kmp-resolver generate-bazel-manifest \
  --coordinate=org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2 \
  --repository=https://repo1.maven.org/maven2 \
  --repository=https://dl.google.com/dl/android/maven2 \
  --platform=jvm --platform=android \
  --platform=linux_x64 --platform=linux_arm64 --platform=macos_arm64 \
  --platform=ios_arm64 --platform=ios_simulator_arm64 \
  --cache-dir=/tmp/kmp-cache \
  --output-manifest-file=kmp_manifest.json

bazel-kmp-resolver check-bazel-manifest \
  --manifest-file=kmp_manifest.json \
  --coordinate=org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2 \
  --repository=https://repo1.maven.org/maven2 \
  --repository=https://dl.google.com/dl/android/maven2 \
  --platform=jvm --platform=android \
  --platform=linux_x64 --platform=linux_arm64 --platform=macos_arm64 \
  --platform=ios_arm64 --platform=ios_simulator_arm64
```

`--platform` takes the following values, each resolved by an Amper resolution of its own. The konan targets are named
exactly like the keys of `KONAN_TARGETS` in rules_kotlin's `kotlin/internal/native/targets.bzl`, so that the Bazel side
needs no mapping table.

| `--platform`          | Variants                                  |
|-----------------------|-------------------------------------------|
| `wasmjs`              | `klib` variants of platform `wasm_js`     |
| `ios_arm64`           | `klib` variants of the konan target       |
| `ios_simulator_arm64` | `klib` variants of the konan target       |
| `linux_arm64`         | `klib` variants of the konan target       |
| `linux_x64`           | `klib` variants of the konan target       |
| `macos_arm64`         | `klib` variants of the konan target       |
| `android`             | `android` variants, packaged `aar` or `jar`, and `redirect` variants of platform `android` |
| `jvm`                 | `jvm` variants, and `redirect` variants of platform `jvm` |

The **resolution inputs** of a manifest are `--coordinate`, `--platform`, `--repository`, `--substitution`,
`--npm-registry` and `--npm-package-version`: they are digested into its `inputsHash`, and `check-bazel-manifest`
takes the same ones. The other options of `generate-bazel-manifest` (`--cache-dir`, credentials, timeouts, Node.js)
change how a resolution runs, not the manifest. `--node-executable` and `--npm-cli-js` are only required with
`--platform=wasmjs`, whose klibs may declare NPM dependencies: no other platform needs Node.js.

`check-bazel-manifest` resolves nothing: it reads the header of the manifest, and exits with status 1 when its
`schemaVersion` is not the one of the resolver, or when its `inputsHash` is not the hash of the given resolution inputs,
telling that the manifest is stale and must be generated again. `bazel-kmp-resolver --version` prints the version of the
resolver.

## Schema version 2

A manifest is deterministic: identical inputs produce byte-identical manifests, with libraries sorted by ID, variants in
platform order and dependencies sorted, whatever the order of the command-line options.

```json
{
  "schemaVersion": 2,
  "resolverVersion": "dev",
  "inputsHash": "55159b59e8b0ef1933e2002c7441b6ae55f6f6daca1f868470b487520cea83ca",
  "askedPlatforms": ["linux_x64"],
  "askedCoordinates": ["io.ktor:ktor-client-curl:3.4.3"],
  "askedRepositories": ["https://repo1.maven.org/maven2"],
  "askedSubstitutions": [],
  "libraries": {
    "io.ktor:ktor-client-curl:3.4.3": {
      "id": "io.ktor:ktor-client-curl:3.4.3",
      "variants": [
        {
          "type": "klib",
          "platform": "linux_x64",
          "id": "io.ktor:ktor-client-curl:3.4.3",
          "variantId": "io.ktor:ktor-client-curl-linuxx64:3.4.3",
          "dependencies": [],
          "exportedDependencies": [
            "io.ktor:ktor-client-core",
            "io.ktor:ktor-http-cio",
            "org.jetbrains.kotlinx:atomicfu",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core"
          ],
          "klibs": [
            {
              "role": "main",
              "artifact": {
                "integrity": "sha512-9QB2Q3Jo0tnJY8/BpkLACZm4TS0En2zUOQyTxeLv/qsBQu+Szer4zXK0E9cuyGa5gyx5WO1+HOZNotoRgGkwxQ==",
                "groupId": "io.ktor",
                "artifactId": "ktor-client-curl-linuxx64",
                "version": "3.4.3",
                "urls": [
                  "https://repo1.maven.org/maven2/io/ktor/ktor-client-curl-linuxx64/3.4.3/ktor-client-curl-linuxx64-3.4.3.klib"
                ]
              }
            },
            {
              "role": "cinterop",
              "artifact": {
                "integrity": "sha512-qwyOGjim/18YR3KgL7cFc0XpWTxOgQlDRKapLbId+cxVszuSaFIVYIh2orEGki9xctpQG/h5lgNdrN73f3B/KA==",
                "groupId": "io.ktor",
                "artifactId": "ktor-client-curl-linuxx64",
                "version": "3.4.3",
                "urls": [
                  "https://repo1.maven.org/maven2/io/ktor/ktor-client-curl-linuxx64/3.4.3/ktor-client-curl-linuxx64-3.4.3-cinterop-libcurl.klib"
                ]
              }
            }
          ],
          "sourceJar": {
            "integrity": "sha512-nnJOAAsph3tmWlJaOl1zqNOXnAYeragIP0I/WezYs9tlvVEO0M7zi6Z+qR9TADjQD1xjkKbaKgOsBw3c9bZvjA==",
            "groupId": "io.ktor",
            "artifactId": "ktor-client-curl-linuxx64",
            "version": "3.4.3",
            "urls": [
              "https://repo1.maven.org/maven2/io/ktor/ktor-client-curl-linuxx64/3.4.3/ktor-client-curl-linuxx64-3.4.3-sources.jar"
            ]
          }
        }
      ]
    }
  }
}
```

The complete manifest of this example, and the ones of other resolutions, are the `testResources/manifest-*.json`
files of this repository.

### Header

| Field                | Content                                                                                                                                                               |
|----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `schemaVersion`      | `2`. It is bumped on every incompatible change of the schema: the manifests of schema version 1, with `wasmjs` variants and none of these header fields, must be generated again. |
| `resolverVersion`    | Version of the resolver that generated the manifest: the release of the binary, like `0.4.0`, or `dev` for a development build.                                       |
| `inputsHash`         | Hash of the resolution inputs the manifest is generated from, see [Inputs hash](#inputs-hash).                                                                         |
| `askedPlatforms`     | `--platform` values, in platform order.                                                                                                                               |
| `askedCoordinates`   | `--coordinate` values, sorted.                                                                                                                                        |
| `askedRepositories`  | `--repository` URLs, in the order they are given, which is the order artifacts are looked up in.                                                                      |
| `askedSubstitutions` | `--substitution` values as `group:artifact=group:artifact:version`, sorted.                                                                                           |
| `libraries`          | The resolved libraries by ID.                                                                                                                                         |

### Libraries

A library is keyed by its ID, `group:artifact:version`: the KMP library (the umbrella module) its variants are the
platform-specific modules of, or the library itself when it is not a variant of a KMP library, like a JVM-only library
or `kotlin-stdlib`, which publishes its JVM jar in its umbrella module.

`variants` lists at most one variant per platform, in **platform order**: the klib platforms first (`wasm_js`,
`ios_arm64`, `ios_simulator_arm64`, `linux_arm64`, `linux_x64`, `macos_arm64`), then `android`, then `jvm`. A library
has a variant for each requested platform whose dependency graph contains it: `kotlin-stdlib` has no native variant, as
it ships with the Kotlin/Native distribution, and a JVM-only library has no `klib` variant. An umbrella module whose
variant of a platform is a module of its own (`available-at`) is the library of that variant; a BOM is not a library.
A module without artifact for the `android` or `jvm` platform but with dependencies, like an umbrella whose variant of
the platform only declares dependencies on other libraries, or a POM-only module, is a library with a `redirect`
variant (see below).

### Variants

Every variant has these fields:

| Field                  | Content                                                                                                                                 |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `type`                 | `klib`, `android`, `jvm` or `redirect`.                                                                                                 |
| `id`                   | ID of its library.                                                                                                                      |
| `variantId`            | `group:artifact:version` of the Maven module resolved for the platform, like `org.jetbrains.kotlinx:kotlinx-coroutines-core-linuxx64:1.10.2`. |
| `dependencies`         | Libraries the library needs on the link path or at runtime, but does not expose to its dependents, as `group:artifact`, sorted.          |
| `exportedDependencies` | Libraries the library exposes to the compile path of its direct dependents, and needs on the link path or at runtime, as `group:artifact`, sorted. |

A dependency `group:artifact` designates the library of the manifest with that `group:artifact` which has a variant of
the same platform: the dependency graph of a platform is resolved once, so there is exactly one. A dependency on a
library without variant of the platform, like `kotlin-stdlib` on native platforms or a JVM library a klib wrongly
depends on, is left out.

#### `klib`

| Field         | Content                                                                                                                                                                                                                       |
|---------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `platform`    | `wasm_js`, or the konan target of the klibs.                                                                                                                                                                                  |
| `klibs`       | The klibs of the variant, each with its `role` and its Maven `artifact`: exactly one `main` klib first, the klib of the library itself, then the `cinterop` klibs binding the C libraries it uses (`<artifact>-<version>-cinterop-<name>.klib`), sorted by file name. |
| `sourceJar`   | Maven artifact of the sources jar, `null` when there is none.                                                                                                                                                                 |
| `npmPackages` | `wasm_js` only, left out when empty: the NPM packages the klib requires at runtime (declared by its embedded `package.json`) with their transitive NPM dependencies, as `{"integrity", "name", "version", "url"}`.           |

#### `android`

| Field       | Content                                                                                                                                                                                                                     |
|-------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `artifact`  | Maven artifact of the `.aar` or the `.jar` of the library.                                                                                                                                                                  |
| `packaging` | `aar` for an Android library; `jar` for a JVM library, or for the JVM variant of a KMP library publishing no Android variant, which Android resolutions fall back to: its `variantId` is then the `*-jvm` module.            |
| `sourceJar` | Maven artifact of the sources jar, `null` when there is none.                                                                                                                                                               |

Every library of the Android dependency graph has an `android` variant, even when it is identical to its `jvm` variant,
so that the Android branch of an alias never falls back to the JVM one.

#### `jvm`

| Field       | Content                                                         |
|-------------|-----------------------------------------------------------------|
| `jar`       | Maven artifact of the jar of the library.                       |
| `sourceJar` | Maven artifact of the sources jar, `null` when there is none.   |

#### `redirect`

| Field      | Content                                                                                      |
|------------|----------------------------------------------------------------------------------------------|
| `platform` | `android` or `jvm`: the platform the variant stands for its dependencies on.                 |

A `redirect` variant has no artifact: the library publishes its variant of the platform as dependencies on other
libraries only, which `dependencies` and `exportedDependencies` list as usual. The Android variants of the JetBrains
Compose libraries (`org.jetbrains.compose.runtime:runtime`, …) are such redirects to the AndroidX Compose libraries
(`androidx.compose.runtime:runtime`, …), and so is a POM-only library aggregating others. Consumers of the library get
what it stands for, as the consumers of a Gradle build do. The klibs of a native platform are always published in a
module of their own, which the umbrella points at (`available-at`), so a klib platform has no `redirect` variant.
`variantId` is the module itself, which has no files to download. The `redirect` type was added to schema version 2
after its first manifests, which remain valid: they have no such variant, their resolutions dropped these modules
along with the edges pointing at them.

### Maven artifacts

| Field                              | Content                                                                                                                                                                                               |
|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `integrity`                        | Subresource integrity string, as accepted by the `integrity` attributes of Bazel: `sha512-…`, else `sha256-…`, else `sha1-…` for the repositories publishing nothing stronger (most JVM-only libraries on Maven Central). |
| `groupId`, `artifactId`, `version` | Coordinates of the module of the artifact, its variant's `variantId`.                                                                                                                                 |
| `urls`                             | URLs of the artifact in each repository it is found in, in the order of the repositories.                                                                                                             |

The klibs of a variant share their coordinates: the last segment of their URLs is their file name.

### Inputs hash

`inputsHash` is the lowercase hexadecimal SHA-256 digest of the UTF-8 JSON object of the normalized resolution inputs,
without whitespace, with these properties in this order:

| Property             | Value                                                                                         |
|----------------------|-----------------------------------------------------------------------------------------------|
| `platforms`          | `--platform` values in platform order, without duplicates.                                    |
| `coordinates`        | `--coordinate` values, sorted, without duplicates.                                            |
| `repositories`       | `--repository` URLs in the order they are given, without duplicates.                          |
| `substitutions`      | `--substitution` values as `group:artifact=group:artifact:version`, sorted; the last value of a repeated key wins. |
| `npmRegistry`        | `--npm-registry`, `https://registry.npmjs.org` when not given.                                |
| `npmPackageVersions` | `--npm-package-version` values as `name=version`, sorted; the last value of a repeated name wins. |

For instance, the `inputsHash` of the example above is the digest of:

```json
{"platforms":["linux_x64"],"coordinates":["io.ktor:ktor-client-curl:3.4.3"],"repositories":["https://repo1.maven.org/maven2"],"substitutions":[],"npmRegistry":"https://registry.npmjs.org","npmPackageVersions":[]}
```

## Bazel targets

The targets of a library are named after `<lib>`, its `group:artifact` with every character but letters and digits
replaced by `_`, like `org_jetbrains_kotlinx_kotlinx_coroutines_core`. Each Maven artifact of the manifest is
downloaded from its `urls`, checked against its `integrity`. The targets of the variants are `<lib>.<platform>`: the
`.`, which the name of a library never contains, keeps them apart from the alias of a library whose artifact ends in
`-android` or `-jvm` (`androidx.datastore:datastore-preferences-android` next to
`androidx.datastore:datastore-preferences`). In the dependencies of a target of platform `<p>`, a dependency `<d>` (a
`group:artifact` of `dependencies` or `exportedDependencies`) is the target of `<d>` for `<p>`.

| Variant                          | Targets                                                                                                                                                                                                                                                                                                                                         |
|----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `klib` of konan target `<t>`     | One `kt_klib_import` per klib, all with `konan_target = "<t>"`: `<lib>.<t>.cinterop_<n>` for the `cinterop` klib at position `<n>` (from 0) among the cinterop klibs of the variant, and `<lib>.<t>` for the `main` klib, whose `deps` are its cinterop imports and the `<d>.<t>` of `dependencies`, and whose `exports` are the `<d>.<t>` of `exportedDependencies`. |
| `klib` of platform `wasm_js`     | The same imports, named `<lib>.wasm_js` and `<lib>.wasm_js.cinterop_<n>`, with the `KtKlibInfo` platform `wasm_js` and no konan target, once rules_kotlin imports Wasm klibs; the `npmPackages` are the NPM dependencies of `<lib>.wasm_js`.                                                                                          |
| `android` packaged `aar`         | `aar_import` named `<lib>.android` of the `artifact`, with the `sourceJar` as `srcjar`, the `<d>.android` of `dependencies` and `exportedDependencies` as `deps` and the ones of `exportedDependencies` as `exports`.                                                                                                                       |
| `android` packaged `jar`         | `jvm_import` named `<lib>.android` of the `artifact`, with the same `srcjar`, `deps` and `exports`.                                                                                                                                                                                                                                        |
| `jvm`                            | `jvm_import` named `<lib>.jvm` of the `jar`, with the `sourceJar` as `srcjar`, the `<d>.jvm` of `dependencies` and `exportedDependencies` as `deps` and the ones of `exportedDependencies` as `exports`.                                                                                                                                   |
| `redirect` of platform `android` | `android_library` named `<lib>.android` without sources, whose `exports` are the `<d>.android` of both `dependencies` and `exportedDependencies`, as the rule has no runtime-only dependencies.                                                                                                                                              |
| `redirect` of platform `jvm`     | `kt_jvm_library` named `<lib>.jvm` without sources, whose `exports` are the `<d>.jvm` of `exportedDependencies` and `runtime_deps` the ones of `dependencies`; a `kt_jvm_library` rather than a `java_library`, so that it forwards the `KtJvmInfo` of its dependencies too.                                                                                                  |

A `kt_klib_import` provides a `KtKlibInfo` whose `platform` is `native` and `konan_target` is the `platform` of the
variant (`wasm_js` and no konan target for a Wasm klib), whose `transitive_klibs` gathers the imports of its `deps` and
`exports`, and whose `exported_klibs` the ones of its `exports`: the cinterop klibs of a library follow its main klib
wherever it is compiled against or linked.

Each library then gets an alias named `<lib>` selecting its target for the platform being built:

```starlark
alias(
    name = "org_jetbrains_kotlinx_kotlinx_coroutines_core",
    actual = select({
        "@rules_kotlin//kotlin/native/platforms:is_ios_arm64": ":org_jetbrains_kotlinx_kotlinx_coroutines_core.ios_arm64",
        "@rules_kotlin//kotlin/native/platforms:is_ios_simulator_arm64": ":org_jetbrains_kotlinx_kotlinx_coroutines_core.ios_simulator_arm64",
        "@rules_kotlin//kotlin/native/platforms:is_linux_arm64": ":org_jetbrains_kotlinx_kotlinx_coroutines_core.linux_arm64",
        "@rules_kotlin//kotlin/native/platforms:is_linux_x64": ":org_jetbrains_kotlinx_kotlinx_coroutines_core.linux_x64",
        "@rules_kotlin//kotlin/native/platforms:is_macos_arm64": ":org_jetbrains_kotlinx_kotlinx_coroutines_core.macos_arm64",
        "@platforms//os:android": ":org_jetbrains_kotlinx_kotlinx_coroutines_core.android",
        "//conditions:default": ":org_jetbrains_kotlinx_kotlinx_coroutines_core.jvm",
    }),
)
```

- The alias has a branch per variant of the library: the main klib import for a konan target, the `android` import for
  `@platforms//os:android`, and the `jvm` import as default. A library without `jvm` variant has no default branch, so
  depending on it for a platform it is not resolved for fails with Bazel's error about no matching condition.
- `wasm_js` variants have no branch, as rules_kotlin has no `config_setting` for Wasm: their imports are depended upon
  by name.

## Pinned manifest workflow

The manifest is meant to be committed next to the `MODULE.bazel` declaring its resolution inputs, so that a build needs
neither the resolver nor Node.js, nor any access to Maven metadata: only the artifacts, by URL and integrity.

1. **Pin**: a target of the module extension (like `bazel run @kmp_maven//:pin`) downloads the release binary of the
   resolver pinned by the extension, runs `generate-bazel-manifest` with the declared inputs, and writes the manifest
   into the workspace. The binaries are the assets of the GitHub releases of this repository, published by
   `.github/workflows/publish.yml`: `bazel-kmp-resolver-linux-x64`, `bazel-kmp-resolver-linux-arm64`,
   `bazel-kmp-resolver-macos-x64`, `bazel-kmp-resolver-macos-arm64` and `bazel-kmp-resolver-windows-x64.exe`.
2. **Fetch**: the extension reads the committed manifest and fails, asking to pin again, when it is stale: either by
   running `check-bazel-manifest` with the declared inputs, or by comparing its `schemaVersion` and `asked*` header
   fields with the declared inputs normalized as above, which needs no binary. It then generates the targets above.

## Follow-up in rules_kotlin

This repository only publishes the resolver binaries and this contract. The rules_kotlin plan following it implements,
in line with steps 5 (dependencies) and 6 (Android and iOS KMP app) of its KMP native rules roadmap:

- A module extension with tags declaring the coordinates, repositories, platforms, substitutions and manifest file, the
  pin target, and the download of the pinned resolver binary (version and SHA-256 per asset) for pinning only.
- A repository per Maven artifact (`urls` and `integrity`) and a hub repository generating the imports and aliases
  above, replacing the `http_file` pins of `examples/kmp` and `examples/kmp_app`, and the upstream `rules_jvm_external`
  change the roadmap planned to rely on.
- `kt_klib_import(klib, konan_target, deps, exports)` of the roadmap, unchanged: one import per klib, cinterop imports in
  the `deps` of the main one; `aar_import` from rules_android for the `aar` packaging.
- Branch precedence in the alias: the `is_<target>` config settings only match the OS and CPU constraints of a konan
  target, which a Linux x86_64 or macOS arm64 host platform also has, so a JVM target built for such a host would select
  the native import. The native branches must only match the platforms native targets are built for, for instance with
  a constraint that only the rules_kotlin konan platforms carry.
