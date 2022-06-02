@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package edu.illinois.cs.cs125.jeed.core

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import com.squareup.moshi.JsonClass
import org.antlr.v4.runtime.misc.Interval
import kotlin.random.Random

fun Source.ParsedSource.contents(location: Mutation.Location): String =
    stream.getText(Interval(location.start, location.end))

fun MutableList<Mutation.Location.SourcePath>.klass(): String =
    findLast { it.type == Mutation.Location.SourcePath.Type.CLASS }?.name ?: error("No current class in path")

fun MutableList<Mutation.Location.SourcePath>.method(): String =
    findLast { it.type == Mutation.Location.SourcePath.Type.METHOD }?.name ?: error("No current method in path")

@JsonClass(generateAdapter = true)
data class SourceMutation(
    val name: String,
    val mutation: Mutation
)

@JsonClass(generateAdapter = true)
data class AppliedSourceMutation(
    val name: String,
    val mutation: AppliedMutation
) {
    constructor(sourceMutation: SourceMutation) : this(sourceMutation.name, AppliedMutation(sourceMutation.mutation))
}

@Suppress("unused", "MemberVisibilityCanBePrivate")
class MutatedSource(
    sources: Sources,
    val originalSources: Sources,
    val mutations: List<AppliedSourceMutation>,
    val appliedMutations: Int,
    val unappliedMutations: Int
) : Source(sources) {
    fun cleaned() = Source(sources.mapValues { removeMutationSuppressions(it.value) })

    @Suppress("LongMethod")
    fun marked(): Source {
        require(mutations.size == 1) { "Can only mark sources that have been mutated once" }
        val mutation = mutations.first()
        return Source(
            sources.mapValues { (name, modified) ->
                if (name != mutation.name) {
                    return@mapValues modified
                }
                val original = originalSources[name] ?: error("Didn't find original sources")
                check(original != modified) { "Didn't find mutation" }

                val originalLines = original.lines()
                val modifiedLines = modified.lines()
                val deltas =
                    DiffUtils.diff(originalLines, modifiedLines).deltas.sortedBy { it.source.position }.toMutableList()
                var i = 0
                val output = mutableListOf<String>()
                while (i < originalLines.size) {
                    val line = originalLines[i]

                    val indentAmount = line.length - line.trimStart().length
                    val currentIndent = " ".repeat(indentAmount)

                    val activeDelta = deltas.firstOrNull()?.let {
                        if (it.source.position == i) {
                            it
                        } else {
                            null
                        }
                    }
                    val sourceLines = activeDelta?.source?.lines
                    val targetLines = activeDelta?.target?.lines
                    val nextLine = when {
                        activeDelta == null -> {
                            i++
                            line
                        }
                        activeDelta.type == DeltaType.CHANGE -> {
                            val originalContent = sourceLines!!.joinToString("\n") {
                                if (it.length < indentAmount) {
                                    "$currentIndent//"
                                } else {
                                    currentIndent + "// " + it.substring(indentAmount)
                                }
                            }
                            i += sourceLines.size
                            deltas.removeAt(0)
                            """
                    |$currentIndent// Modified by ${mutation.mutation.mutationType.mutationName()}. Originally:
                    |$originalContent
                    |${targetLines!!.joinToString("\n")}
                            """.trimMargin()
                        }
                        activeDelta.type == DeltaType.DELETE -> {
                            val originalContent = sourceLines!!.joinToString("\n") {
                                if (it.length < indentAmount) {
                                    "$currentIndent//"
                                } else {
                                    currentIndent + "// " + it.substring(indentAmount)
                                }
                            }
                            i += sourceLines.size
                            """
                    |$currentIndent// Removed by ${mutation.mutation.mutationType.mutationName()}. Originally:
                    |$originalContent
                            """.trimMargin()
                        }
                        else -> error("Invalid delta type: ${activeDelta.type}")
                    }
                    output += nextLine
                }
                output.joinToString("\n")
            }
        )
    }

    companion object {
        private val matchMutationSuppression = Regex("""\s*// mutate-disable.*$""")
        fun removeMutationSuppressions(contents: String) = contents.lines().joinToString("\n") {
            matchMutationSuppression.replace(it, "")
        }
    }
}

class Mutater(
    private val originalSource: Source,
    shuffle: Boolean,
    seed: Int,
    types: Set<Mutation.Type>
) {
    private val random = Random(seed)
    private val mutations = originalSource.sources.keys.map { name ->
        Mutation.find<Mutation>(originalSource.getParsed(name), originalSource.type)
            .map { mutation -> SourceMutation(name, mutation) }
            .filter {
                types.contains(it.mutation.mutationType)
            }
    }.flatten().let {
        if (shuffle) {
            it.shuffled(random)
        } else {
            it
        }
    }
    private val availableMutations: MutableList<SourceMutation> = mutations.toMutableList()
    val appliedMutations: MutableList<AppliedSourceMutation> = mutableListOf()

    val size: Int
        get() = availableMutations.size

    val sources = originalSource.sources.toMutableMap()
    internal fun apply(): Sources {
        check(availableMutations.isNotEmpty()) { "No more mutations to apply" }
        availableMutations.removeAt(0).also { sourceMutation ->
            val original = sources[sourceMutation.name] ?: error("Couldn't find key that should be there")
            val modified = sourceMutation.mutation.apply(original, random)
            check(original != modified) { "Mutation did not change source" }

            appliedMutations.add(AppliedSourceMutation(sourceMutation))
            availableMutations.removeIf { it.mutation.overlaps(sourceMutation.mutation) }
            availableMutations.filter { it.mutation.after(sourceMutation.mutation) }.forEach {
                it.mutation.shift(modified.length - original.length)
            }

            sources[sourceMutation.name] = modified
        }
        return Sources(sources)
    }

    fun mutate(limit: Int = 1): MutatedSource {
        check(appliedMutations.isEmpty()) { "Some mutations already applied" }
        @Suppress("UnusedPrivateMember")
        for (unused in 0 until limit) {
            if (availableMutations.isEmpty()) {
                break
            }
            apply()
        }
        return MutatedSource(
            Sources(sources),
            originalSource.sources,
            appliedMutations,
            appliedMutations.size,
            availableMutations.size
        )
    }
}

fun Source.mutater(shuffle: Boolean = true, seed: Int = Random.nextInt(), types: Set<Mutation.Type> = ALL) =
    Mutater(this, shuffle, seed, types = types)

fun Source.mutate(
    shuffle: Boolean = true,
    seed: Int = Random.nextInt(),
    limit: Int = 1,
    types: Set<Mutation.Type> = Mutation.Type.values().toSet()
) =
    Mutater(this, shuffle, seed, types).mutate(limit)

fun SourceMutation.suppressed() = mutation.location.line.lines().any { line ->
    line.split("""//""").let { parts ->
        parts.size == 2 && (
            parts[1].split(" ").contains("mutate-disable") ||
                parts[1].split(" ").contains(mutation.mutationType.suppressionComment())
            )
    }
}

fun Source.allMutations(
    suppressWithComments: Boolean = true,
    random: Random = Random,
    types: Set<Mutation.Type> = ALL
): List<MutatedSource> {
    val mutations = sources.keys.map { name ->
        Mutation.find<Mutation>(getParsed(name), type).map { mutation -> SourceMutation(name, mutation) }
    }.flatten()
        .filter { types.contains(it.mutation.mutationType) }
        .filter { !suppressWithComments || !it.suppressed() }

    return mutations.map { sourceMutation ->
        val modifiedSources = sources.copy().toMutableMap()
        val original =
            modifiedSources[sourceMutation.name] ?: error("Couldn't find a source that should be there")
        val modified = sourceMutation.mutation.apply(original, random)
        check(original != modified) { "Mutation did not change source" }
        modifiedSources[sourceMutation.name] = modified
        MutatedSource(
            Sources(modifiedSources),
            sources,
            listOf(AppliedSourceMutation(sourceMutation)),
            1,
            mutations.size - 1
        )
    }
}

fun Source.mutationStream(
    suppressWithComments: Boolean = true,
    random: Random = Random,
    types: Set<Mutation.Type> = ALL,
    retryCount: Int = 32
) = sequence {
    val mutations = sources.keys.asSequence().map { name ->
        Mutation.find<Mutation>(getParsed(name), type).map { mutation -> SourceMutation(name, mutation) }
    }.flatten()
        .filter { types.contains(it.mutation.mutationType) }
        .filter { !suppressWithComments || !it.suppressed() }
        .toMutableList()

    val seen = mutableSetOf<String>()
    var retries = 0
    val remaining = mutableMapOf<SourceMutation, Int>()
    @Suppress("LoopWithTooManyJumpStatements")
    while (true) {
        val mutation = mutations.shuffled(random).first()
        if (mutation !in remaining) {
            remaining[mutation] = mutation.mutation.estimatedCount
        }
        mutation.mutation.reset()
        val modifiedSources = sources.copy().toMutableMap()
        val original = modifiedSources[mutation.name] ?: error("Couldn't find a source that should be there")
        val modified = mutation.mutation.apply(original, random)
        check(original != modified) { "Mutation did not change source" }
        modifiedSources[mutation.name] = modified
        val source = MutatedSource(
            Sources(modifiedSources),
            sources,
            listOf(AppliedSourceMutation(mutation)),
            1,
            mutations.size - 1
        )
        if (source.md5 !in seen) {
            retries = 0
            seen += source.md5
            yield(source)
            remaining[mutation] = remaining[mutation]!! - 1
            if (remaining[mutation] == 0) {
                mutations.remove(mutation)
                if (mutations.isEmpty()) {
                    return@sequence
                }
            }
        } else {
            if (retries++ >= retryCount) {
                return@sequence
            }
        }
    }
}

@Suppress("NestedBlockDepth", "LongParameterList")
fun Source.allFixedMutations(
    suppressWithComments: Boolean = true,
    random: Random = Random,
    types: Set<Mutation.Type> = ALL,
    nonFixedMax: Int = 4,
    retryCount: Int = 8
): List<MutatedSource> {
    val mutations = sources.keys.asSequence()
        .map {
            Mutation.find<Mutation>(getParsed(it), type).map { mutation -> SourceMutation(name, mutation) }
        }.flatten()
        .filter { types.contains(it.mutation.mutationType) }
        .filter { !suppressWithComments || !it.suppressed() }
        .toMutableList()

    val mutatedSources = mutableListOf<MutatedSource>()

    for (mutation in mutations) {
        val seen = mutableSetOf<String>()
        var retries = 0
        val count = if (mutation.mutation.fixedCount) {
            mutation.mutation.estimatedCount
        } else {
            mutation.mutation.estimatedCount.coerceAtMost(nonFixedMax)
        }
        @Suppress("UnusedPrivateMember")
        for (unused in 0 until count) {
            mutation.mutation.reset()
            val modifiedSources = sources.copy().toMutableMap()
            val original = modifiedSources[mutation.name] ?: error("Couldn't find a source that should be there")
            val modified = mutation.mutation.apply(original, random)
            check(original != modified) { "Mutation did not change source" }
            modifiedSources[mutation.name] = modified
            val source = MutatedSource(
                Sources(modifiedSources),
                sources,
                listOf(AppliedSourceMutation(mutation)),
                1,
                mutations.size - 1
            )
            if (source.md5 !in seen) {
                retries = 0
                seen += source.md5
                mutatedSources += source
            } else {
                if (retries++ >= retryCount) {
                    break
                }
            }
        }
    }
    return mutatedSources
}

@JsonClass(generateAdapter = true)
data class MutationsArguments(val limit: Int = 4, val suppressWithComments: Boolean = true)

class MutationsFailed(errors: List<SourceError>) : JeedError(errors) {
    override fun toString(): String {
        return "errors were encountered while mutating sources: ${errors.joinToString(separator = ",")}"
    }
}

@JsonClass(generateAdapter = true)
data class MutationsResults(val source: Map<String, String>, val mutatedSources: List<MutatedSource>) {
    @JsonClass(generateAdapter = true)
    data class MutatedSource(
        val mutatedSource: String,
        val mutatedSources: Map<String, String>,
        val mutation: AppliedMutation
    )
}

@Throws(MutationsFailed::class)
fun Source.mutations(mutationsArguments: MutationsArguments = MutationsArguments()): MutationsResults {
    try {
        val mutatedSources = mutationStream(mutationsArguments.suppressWithComments)
            .map {
                require(it.mutations.size == 1) { "Stream applied multiple mutations" }
                MutationsResults.MutatedSource(
                    it.mutations.first().name,
                    it.sources.sources,
                    it.mutations.first().mutation
                )
            }
            .take(mutationsArguments.limit)
            .toList()
        return MutationsResults(this.sources, mutatedSources)
    } catch (e: JeedParsingException) {
        throw MutationsFailed(e.errors)
    }
}
