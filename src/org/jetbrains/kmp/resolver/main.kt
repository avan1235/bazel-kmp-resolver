package org.jetbrains.kmp.resolver

import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption

suspend fun main(args: Array<String>) {
    BazelKmpResolverCommand().main(args)
}

/** Root command of the resolver, whose subcommands generate and check manifests. */
class BazelKmpResolverCommand : SuspendingNoOpCliktCommand("bazel-kmp-resolver") {
    init {
        versionOption(resolverVersion)
        subcommands(GenerateBazelManifestCommand(), CheckBazelManifestCommand())
    }

    override fun help(context: Context): String =
        "Resolves Kotlin Multiplatform dependencies into a manifest consumed by Bazel, see docs/manifest.md."
}
