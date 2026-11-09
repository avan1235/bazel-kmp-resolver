# bazel-kmp-resolver

A resolver of Kotlin Multiplatform dependencies that produces a manifest of that resolution, to be used notably in Bazel
repository rules

## Usage

The resolver is not meant to be use standalone, it will be used in a Bazel repository rule: the manifest it writes, and
how Bazel targets are generated from it, are specified in [docs/manifest.md](docs/manifest.md).

But you could try it out by running:

```shell
./kotlin run --module bazel-kmp-resolver --main-class org.jetbrains.kmp.resolver.MainKt -- generate-bazel-manifest --output-manifest-file=./build/kmp-local-cache/manifest.json --cache-dir=./build/kmp-local-cache --repository=https://repo1.maven.org/maven2 --repository=https://dl.google.com/dl/android/maven2 --repository-credentials-file=./testResources/credentials.json --coordinate=io.ktor:ktor-client-cio:3.5.0 --platform=jvm --platform=android --platform=linux_x64 --platform=ios_arm64
```

`--platform` can be specified multiple times, among:

- `jvm`: the jar variants of KMP libraries, along with JVM-only libraries;
- `android`: the `aar` or `jar` variants of every library of the Android dependency graph;
- `wasmjs`: the klib variants of KMP libraries for Wasm, along with the NPM packages they declare;
- `linux_x64`, `linux_arm64`, `macos_arm64`, `ios_arm64`, `ios_simulator_arm64`: the klib variants of KMP libraries for
  these Kotlin/Native targets, named like the konan targets of rules_kotlin, with the cinterop klibs of the C libraries
  they bind.

Only `wasmjs` needs Node.js, to resolve the NPM dependencies its klibs declare: it requires `--node-executable` and
`--npm-cli-js`, for instance the distribution provisioned for the tests by `./kotlin do provisionNode`:

```shell
./kotlin run --module bazel-kmp-resolver --main-class org.jetbrains.kmp.resolver.MainKt -- generate-bazel-manifest --output-manifest-file=./build/kmp-local-cache/manifest.json --cache-dir=./build/kmp-local-cache --repository=https://repo1.maven.org/maven2 --coordinate=io.ktor:ktor-client-core:3.4.3 --platform=wasmjs --node-executable=./build/node/dist/bin/node --npm-cli-js=./build/node/dist/lib/node_modules/npm/bin/npm-cli.js
```

### Pinned manifests

The manifest records the resolution inputs it is generated from (`--coordinate`, `--platform`, `--repository`,
`--substitution`, `--ignore-published-checksums`, `--npm-registry`, `--npm-package-version`) as an `inputsHash`, along with its `schemaVersion` and the
`resolverVersion`. `check-bazel-manifest` takes the same inputs and exits with a non-zero status when the manifest was
generated from other inputs or follows another schema, telling that a committed manifest must be generated again:

```shell
./kotlin run --module bazel-kmp-resolver --main-class org.jetbrains.kmp.resolver.MainKt -- check-bazel-manifest --manifest-file=./build/kmp-local-cache/manifest.json --repository=https://repo1.maven.org/maven2 --repository=https://dl.google.com/dl/android/maven2 --coordinate=io.ktor:ktor-client-cio:3.5.0 --platform=jvm --platform=android --platform=linux_x64 --platform=ios_arm64
```

`--version` prints the version of the resolver, the one recorded in the manifests it generates.

### Authentication

Private repositories are authenticated in two ways, both optional:

- `--repository-credentials-file`: a JSON list of `{"repositoryUrl", "username", "password"}` entries, sent as basic
  authentication with the requests under each repository URL (Bazel's `kmp.bzl` fills it from `NETRC`).
- `--credential-helper=[scope=]path`: a [Bazel credential helper](https://github.com/EngFlow/credential-helper-spec),
  run as `path get` with `{"uri": "..."}` on its standard input, whose `headers` are added to every request of a matching
  host. The syntax and matching rules are those of Bazel's `--credential_helper` (`%workspace%` is not expanded, the
  caller passes an absolute path or a name found on `PATH`). Answers are cached per URI until their `expires` timestamp,
  or for `--credential-helper-cache-duration-ms` (30 minutes) without one; a helper not answering within
  `--credential-helper-timeout-ms` (10 seconds), exiting with an error, or printing an invalid answer fails the
  resolution.

Both go through the same HTTP client, which authenticates every request of the resolver: a host a helper applies to is
authenticated by that helper alone, even when it returns no header, and the credentials file serves the other hosts,
as in Bazel.

## Formatting

To format, run:

```shell
./kotlin do format
```

## Tests

To run tests, run:

```shell
./kotlin test
```

Most tests resolve real coordinates and compare the manifest of the resolution with a recorded one,
`testResources/manifest-*.json`. To record them again from the current resolutions, then review their diff, run:

```shell
./kotlin test -m bazel-kmp-resolver --include-classes='org.jetbrains.kmp.resolver.MultiplatformResolverTest' --jvm-args="-Dbazel.kmp.resolver.record-manifests=$PWD/testResources"
```