package edu.illinois.cs.cs125.jeed

import com.puppycrawl.tools.checkstyle.Checker
import com.puppycrawl.tools.checkstyle.ConfigurationLoader
import com.puppycrawl.tools.checkstyle.PackageObjectFactory
import com.puppycrawl.tools.checkstyle.PropertiesExpander
import mu.KotlinLogging
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

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
    fun check(source: Source) {

    }
}
val defaultChecker = run {
    ConfiguredChecker(object : Any() {}::class.java.getResource("/checkstyle/default.xml").readText())
}

fun Source.checkstyle() {
    defaultChecker.check(this)
}
