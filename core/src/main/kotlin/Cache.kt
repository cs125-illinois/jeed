@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Instant
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

const val JEED_DEFAULT_COMPILATION_CACHE_SIZE_MB = 256L
@Suppress("TooGenericExceptionCaught")
val compilationCacheSizeMB = try {
    System.getenv("JEED_COMPILATION_CACHE_SIZE")?.toLong() ?: JEED_DEFAULT_COMPILATION_CACHE_SIZE_MB
} catch (e: Exception) {
    logger.warn("Bad value for JEED_COMPILATION_CACHE_SIZE")
    JEED_DEFAULT_COMPILATION_CACHE_SIZE_MB
}

const val JEED_DEFAULT_USE_CACHE = true
@Suppress("TooGenericExceptionCaught")
val useCompilationCache = try {
    System.getenv("JEED_USE_CACHE")?.toBoolean() ?: JEED_DEFAULT_USE_CACHE
} catch (e: Exception) {
    logger.warn("Bad value for JEED_USE_CACHE")
    JEED_DEFAULT_USE_CACHE
}

const val MB_TO_BYTES = 1024 * 1024
val compilationCache: Cache<String, CachedCompilationResults> =
    Caffeine.newBuilder()
        .maximumWeight(compilationCacheSizeMB * MB_TO_BYTES)
        .weigher<String, CachedCompilationResults> { _, cachedCompilationResults ->
            cachedCompilationResults.fileManager.size
        }
        .recordStats()
        .build()

class CachedCompilationResults(
    val compiled: Instant,
    val messages: List<CompilationMessage>,
    val fileManager: JeedFileManager,
    val compilerName: String,
    val compilationArguments: CompilationArguments? = null,
    val kompilationArguments: KompilationArguments? = null
)

object MoreCacheStats {
    var hits: Int = 0
    var misses: Int = 0
}

@Suppress("ReturnCount")
fun Source.tryCache(
    compilationArguments: CompilationArguments,
    started: Instant,
    compilerName: String
): CompiledSource? {
    if (!compilationArguments.useCache) {
        return null
    }
    val cachedResult = compilationCache.getIfPresent(md5)
    if (cachedResult == null) {
        MoreCacheStats.misses++
        return null
    }
    MoreCacheStats.hits++

    val cachedCompilationArguments = cachedResult.compilationArguments ?: require {
        "Cached compilation result missing arguments"
    }
    if (cachedResult.compilerName != compilerName) {
        return null
    }
    if (cachedCompilationArguments != compilationArguments) {
        return null
    }
    val parentClassLoader =
        compilationArguments.parentClassLoader ?: ClassLoader.getSystemClassLoader()
    return CompiledSource(
        this,
        cachedResult.messages,
        cachedResult.compiled,
        Interval(started, Instant.now()),
        JeedClassLoader(cachedResult.fileManager, parentClassLoader),
        cachedResult.fileManager,
        compilerName,
        true
    )
}

fun CompiledSource.cache(compilationArguments: CompilationArguments) {
    if (cached || !compilationArguments.useCache) {
        return
    }
    GlobalScope.launch {
        compilationCache.put(
            source.md5,
            CachedCompilationResults(
                compiled,
                messages,
                fileManager,
                compilerName,
                compilationArguments = compilationArguments
            )
        )
    }.also {
        if (compilationArguments.waitForCache) {
            runBlocking { it.join() }
        }
    }
}

@Suppress("ReturnCount")
fun Source.tryCache(
    kompilationArguments: KompilationArguments,
    started: Instant,
    compilerName: String
): CompiledSource? {
    if (!kompilationArguments.useCache) {
        return null
    }
    val cachedResult = compilationCache.getIfPresent(md5)
    if (cachedResult == null) {
        MoreCacheStats.misses++
        return null
    }
    MoreCacheStats.hits++
    val cachedKompilationArguments = cachedResult.kompilationArguments ?: require {
        "Cached kompilation result missing arguments"
    }
    if (cachedResult.compilerName != compilerName) {
        return null
    }
    if (cachedKompilationArguments != kompilationArguments) {
        return null
    }
    return CompiledSource(
        this,
        cachedResult.messages,
        cachedResult.compiled,
        Interval(started, Instant.now()),
        JeedClassLoader(cachedResult.fileManager, kompilationArguments.parentClassLoader),
        cachedResult.fileManager,
        compilerName,
        true
    )
}

fun CompiledSource.cache(kompilationArguments: KompilationArguments) {
    if (cached || !kompilationArguments.useCache) {
        return
    }
    GlobalScope.launch {
        compilationCache.put(
            source.md5,
            CachedCompilationResults(
                compiled,
                messages,
                fileManager,
                compilerName,
                kompilationArguments = kompilationArguments
            )
        )
    }.also {
        if (kompilationArguments.waitForCache) {
            runBlocking { it.join() }
        }
    }
}
