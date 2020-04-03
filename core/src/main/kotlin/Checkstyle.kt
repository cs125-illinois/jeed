package edu.illinois.cs.cs125.jeed.core

import com.puppycrawl.tools.checkstyle.Checker
import com.puppycrawl.tools.checkstyle.ConfigurationLoader
import com.puppycrawl.tools.checkstyle.PackageObjectFactory
import com.puppycrawl.tools.checkstyle.PropertiesExpander
import com.puppycrawl.tools.checkstyle.api.FileSetCheck
import com.puppycrawl.tools.checkstyle.api.FileText
import com.puppycrawl.tools.checkstyle.api.SeverityLevel
import com.squareup.moshi.JsonClass
import java.io.ByteArrayInputStream
import java.io.File
import org.xml.sax.InputSource

@JsonClass(generateAdapter = true)
data class CheckstyleArguments(
    val sources: Set<String>? = null,
    val failOnError: Boolean = false
)
@JsonClass(generateAdapter = true)
class CheckstyleError(
    val severity: String,
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

class ConfiguredChecker(configurationString: String) {
    private val checker: Checker
    init {
        val configuration = ConfigurationLoader.loadConfiguration(
                InputSource(ByteArrayInputStream(configurationString.toByteArray(Charsets.UTF_8))),
                PropertiesExpander(System.getProperties()),
                ConfigurationLoader.IgnoredModulesOptions.OMIT
        ) ?: error("could not create checkstyle configuration")

        val checkerClass = Checker::class.java
        checker = PackageObjectFactory(
                checkerClass.packageName, checkerClass.classLoader
        ).createModule(configuration.name) as Checker
        checker.setModuleClassLoader(checkerClass.classLoader)
        checker.configure(configuration)
    }
    fun check(sources: Map<String, String>): Map<String, List<CheckstyleError>> {
        return sources.mapValues { source ->
            this.checker.processString(source.key, source.value)
        }
    }
}

fun Checker.processString(name: String, source: String): List<CheckstyleError> {
    val file = File(if (name.endsWith(".java")) name else "$name.java")
    val contents = FileText(file, source.lines())

    val field = this::class.java.getDeclaredField("fileSetChecks")
    field.isAccessible = true
    val anyChecks = field.get(this) as List<*>
    val checks = anyChecks.filterIsInstance<FileSetCheck>()
    assert(anyChecks.size == checks.size)
    assert(checks.isNotEmpty())

    val results: MutableSet<CheckstyleError> = mutableSetOf()
    @Suppress("TooGenericExceptionCaught")
    try {
        checks.map {
            it.process(file, contents)
        }.flatten().forEach {
            results.add(CheckstyleError(
                    it.severityLevel.toString(),
                    SourceLocation(name, it.lineNo, it.columnNo),
                    it.message
            ))
        }
    } catch (e: Exception) {
        results.add(CheckstyleError(
                SeverityLevel.ERROR.toString(),
                SourceLocation(name, 1, 1),
                e.getStackTraceAsString()
        ))
    }
    return results.toList()
}

val defaultChecker = run {
    ConfiguredChecker(object {}::class.java.getResource("/checkstyle/default.xml").readText())
}
@Throws(CheckstyleFailed::class)
fun Source.checkstyle(checkstyleArguments: CheckstyleArguments = CheckstyleArguments()): CheckstyleResults {
    require(type == Source.FileType.JAVA) { "Can't run checkstyle on non-Java sources" }

    val names = checkstyleArguments.sources ?: sources.keys
    val checkstyleResults = defaultChecker.check(sources.filter {
        names.contains(it.key)
    }).values.flatten().map {
        CheckstyleError(it.severity, mapLocation(it.location), it.message)
    }.sortedWith(compareBy({ it.location.source }, { it.location.line }))

    if (checkstyleArguments.failOnError && checkstyleResults.any { it.severity == "error" }) {
        throw CheckstyleFailed(checkstyleResults)
    }
    return CheckstyleResults(checkstyleResults)
}
