package edu.illinois.cs.cs125.jeed.core

import java.util.concurrent.TimeUnit

private const val COROUTINE_INIT_TIMEOUT = 10000L

val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")

@Suppress("BlockingMethodInNonBlockingContext", "MagicNumber")
suspend fun warm(indent: Int = 4) {
    logger.info(
        Source.fromSnippet(
            """System.out.println("javac initialized");""",
            SnippetArguments(indent = indent)
        ).also {
            it.checkstyle(CheckstyleArguments(failOnError = true))
            it.complexity()
        }.compile().execute().output
    )
    logger.info(
        Source.fromSnippet(
            """println("kotlinc initialized")""",
            SnippetArguments(indent = indent, fileType = Source.FileType.KOTLIN)
        ).also {
            it.ktLint(KtLintArguments(failOnError = true))
        }.kompile().execute().output
    )
    logger.info(
        Source.fromSnippet(
            """
                import kotlinx.coroutines.*
                GlobalScope.launch {
                    delay(1)
                    println("coroutine isolation initialized")
                }
            """.trimIndent(),
            SnippetArguments(indent = indent, fileType = Source.FileType.KOTLIN)
        ).kompile().execute(SourceExecutionArguments(waitForShutdown = true, timeout = COROUTINE_INIT_TIMEOUT)).output
    )
    if (!isWindows) {
        logger.info(
            ProcessBuilder(listOf("/bin/sh", "-c", "docker pull ${ContainerExecutionArguments.DEFAULT_IMAGE}"))
                .start().also {
                    it.waitFor(60, TimeUnit.SECONDS)
                }.inputStream.bufferedReader().readText()
        )
    }
}
