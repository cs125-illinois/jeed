package edu.illinois.cs.cs125.jeed.core

private const val COROUTINE_INIT_TIMEOUT = 10000L

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
}
