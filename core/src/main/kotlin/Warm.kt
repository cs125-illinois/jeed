package edu.illinois.cs.cs125.jeed.core

import java.util.concurrent.TimeUnit

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
        ProcessBuilder(listOf("/bin/sh", "-c", "docker pull ${ContainerExecutionArguments.DEFAULT_IMAGE}"))
            .start().also {
                it.waitFor(60, TimeUnit.SECONDS)
            }.inputStream.bufferedReader().readText()
    )
}
