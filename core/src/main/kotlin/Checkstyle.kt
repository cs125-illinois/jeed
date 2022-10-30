package edu.illinois.cs.cs125.jeed.core

import com.puppycrawl.tools.checkstyle.Checker
import com.puppycrawl.tools.checkstyle.ConfigurationLoader
import com.puppycrawl.tools.checkstyle.PackageObjectFactory
import com.puppycrawl.tools.checkstyle.PropertiesExpander
import com.puppycrawl.tools.checkstyle.api.FileSetCheck
import com.puppycrawl.tools.checkstyle.api.FileText
import com.puppycrawl.tools.checkstyle.api.SeverityLevel
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

@JsonClass(generateAdapter = true)
data class CheckstyleArguments(
    val sources: Set<String>? = null,
    val failOnError: Boolean = false,
    val skipUnmapped: Boolean = true,
    val suppressions: Set<String> = setOf()
)

@JsonClass(generateAdapter = true)
class CheckstyleError(
    val severity: String,
    val key: String?,
    location: SourceLocation,
    message: String
) : AlwaysLocatedSourceError(location, message)

class CheckstyleFailed(errors: List<CheckstyleError>) : AlwaysLocatedJeedError(errors) {
    override fun toString(): String {
        return "checkstyle errors were encountered: ${errors.joinToString(separator = ",")}"
    }
}

@JsonClass(generateAdapter = true)
data class CheckstyleResults(val errors: List<CheckstyleError>)

private const val INDENTATION_PATH =
    "/module[@name='Checker']/module[@name='TreeWalker']/module[@name='Indentation']"
private const val DEFAULT_CHECKSTYLE_INDENTATION = 4

class ConfiguredChecker(configurationString: String) {
    private val checker: Checker
    val indentation: Int?

    init {
        val configuration = ConfigurationLoader.loadConfiguration(
            InputSource(ByteArrayInputStream(configurationString.toByteArray(Charsets.UTF_8))),
            PropertiesExpander(System.getProperties()),
            ConfigurationLoader.IgnoredModulesOptions.OMIT
        ) ?: error("could not create checkstyle configuration")

        val configurationDocument = DocumentBuilderFactory.newInstance().also {
            it.isCoalescing = false
            it.isValidating = false
            it.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder().parse(InputSource(StringReader(configurationString)))
        @Suppress("TooGenericExceptionCaught")
        indentation = try {
            XPathFactory.newInstance().newXPath().evaluate(
                INDENTATION_PATH,
                configurationDocument,
                XPathConstants.NODE
            ) as Node
            try {
                (
                    XPathFactory.newInstance().newXPath().evaluate(
                        "$INDENTATION_PATH/property[@name='basicOffset']",
                        configurationDocument,
                        XPathConstants.NODE
                    ) as Node
                    ).attributes.getNamedItem("value").nodeValue.toInt()
            } catch (e: Exception) {
                DEFAULT_CHECKSTYLE_INDENTATION
            }
        } catch (e: Exception) {
            null
        }

        val checkerClass = Checker::class.java
        checker = PackageObjectFactory(
            checkerClass.packageName,
            checkerClass.classLoader
        ).createModule(configuration.name) as Checker
        checker.setModuleClassLoader(checkerClass.classLoader)
        checker.configure(configuration)
    }

    suspend fun check(sources: Map<String, String>): Map<String, List<CheckstyleError>> {
        return sources.mapValues { source ->
            this.checker.processString(source.key, source.value)
        }
    }
}

private val limiter = Semaphore(1)

suspend fun Checker.processString(name: String, source: String): List<CheckstyleError> {
    val file = File(if (name.endsWith(".java")) name else "$name.java")
    val contents = FileText(file, source.lines())

    val field = this::class.java.getDeclaredField("fileSetChecks")
    field.isAccessible = true
    val anyChecks = field.get(this) as List<*>
    val checks = anyChecks.filterIsInstance<FileSetCheck>()
    assert(anyChecks.size == checks.size)
    assert(checks.isNotEmpty())

    limiter.withPermit {
        val results: MutableSet<CheckstyleError> = mutableSetOf()
        @Suppress("TooGenericExceptionCaught")
        try {
            checks.map {
                it.process(file, contents)
            }.flatten().forEach {
                results.add(
                    CheckstyleError(
                        it.severityLevel.toString(),
                        it.key!!,
                        SourceLocation(name, it.lineNo, it.columnNo),
                        it.violation
                    )
                )
            }
        } catch (e: Exception) {
            results.add(
                CheckstyleError(
                    SeverityLevel.ERROR.toString(),
                    null,
                    SourceLocation(name, 1, 1),
                    e.getStackTraceAsString()
                )
            )
        }
        return results.toList()
    }
}

val defaultChecker = run {
    object {}::class.java.getResource("/checkstyle/default.xml")?.readText()?.let { ConfiguredChecker(it) }
}

private val incorrectLevelRegex = """incorrect indentation level (\d+)""".toRegex()
private val expectedLevelRegex = """expected level should be (\d+)""".toRegex()

@Throws(CheckstyleFailed::class)
suspend fun Source.checkstyle(
    checkstyleArguments: CheckstyleArguments = CheckstyleArguments(),
    checker: ConfiguredChecker? = defaultChecker
): CheckstyleResults {
    require(checker != null) { "Must pass a configured checker" }
    require(type == Source.FileType.JAVA) { "Can't run checkstyle on non-Java sources" }

    val names = checkstyleArguments.sources ?: sources.keys
    val checkstyleResults = checker.check(
        sources.filter {
            names.contains(it.key)
        }
    ).values.flatten().mapNotNull { error ->
        val mappedLocation = try {
            mapLocation(error.location)
        } catch (e: SourceMappingException) {
            if (!checkstyleArguments.skipUnmapped) {
                throw e
            }
            null
        }
        if (mappedLocation != null && error.key !in checkstyleArguments.suppressions) {
            val message = if (error.key?.startsWith("indentation") == true) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    val addedIndent = leadingIndentation(error.location)
                    val (incorrectMessage, incorrectAmount) =
                        incorrectLevelRegex.find(error.message)?.groups?.let { match ->
                            Pair(match[0]?.value, match[1]?.value?.toInt())
                        } ?: error("Couldn't parse indentation error")
                    val (expectedMessage, expectedAmount) =
                        expectedLevelRegex.find(error.message)?.groups?.let { match ->
                            Pair(match[0]?.value, match[1]?.value?.toInt())
                        } ?: error("Couldn't parse indentation error")
                    error.message
                        .replace(incorrectMessage!!, "incorrect indentation level ${incorrectAmount!! - addedIndent}")
                        .replace(expectedMessage!!, "expected level should be ${expectedAmount!! - addedIndent}")
                } catch (_: Exception) {
                    error.message
                }
            } else {
                error.message
            }
            CheckstyleError(error.severity, error.key, mappedLocation, message)
        } else {
            null
        }
    }.sortedWith(compareBy({ it.location.source }, { it.location.line }))

    if (checkstyleArguments.failOnError && checkstyleResults.any { it.severity == "error" }) {
        throw CheckstyleFailed(checkstyleResults)
    }
    return CheckstyleResults(checkstyleResults)
}
