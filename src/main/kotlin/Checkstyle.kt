package edu.illinois.cs.cs125.jeed

import com.puppycrawl.tools.checkstyle.Checker
import com.puppycrawl.tools.checkstyle.ConfigurationLoader
import com.puppycrawl.tools.checkstyle.PackageObjectFactory
import com.puppycrawl.tools.checkstyle.PropertiesExpander
import com.puppycrawl.tools.checkstyle.api.FileSetCheck
import com.puppycrawl.tools.checkstyle.api.FileText
import com.puppycrawl.tools.checkstyle.api.SeverityLevel
import mu.KotlinLogging
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.File

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

class CheckstyleError(
        val severity: String,
        location: SourceLocation,
        message: String
) : SourceError(location, message)

data class CheckstyleResults(val errors: Map<String, List<CheckstyleError>>)
class ConfiguredChecker(configurationString: String) {
    val checker: Checker
    init {
        val configuration = ConfigurationLoader.loadConfiguration(
                InputSource(ByteArrayInputStream(configurationString.toByteArray(Charsets.UTF_8))),
                PropertiesExpander(System.getProperties()),
                ConfigurationLoader.IgnoredModulesOptions.OMIT
        ) ?: error("could not create checkstyle configuration")

        val checkerClass = Checker::class.java
        checker = PackageObjectFactory(checkerClass.packageName, checkerClass.classLoader).createModule(configuration.name) as Checker
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
    ConfiguredChecker(object : Any() {}::class.java.getResource("/checkstyle/default.xml").readText())
}

fun Source.checkstyle(names: Set<String> = this.sources.keys.toSet()): CheckstyleResults {
    return CheckstyleResults(defaultChecker.check(this.sources.filter { names.contains(it.key) }).mapValues {
        it.value.map { error ->
            CheckstyleError(error.severity, this.mapLocation(error.location), error.message ?: "")
        }
    })
}
