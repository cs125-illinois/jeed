package edu.illinois.cs.cs125.janini

import mu.KotlinLogging
import java.io.*
import java.lang.module.ModuleFinder
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import javax.lang.model.element.Modifier
import javax.lang.model.element.NestingKind
import javax.tools.JavaFileObject

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}
private val globalClassLoader = ClassLoader.getSystemClassLoader()

private class Listing(val entry: Map.Entry<String, URL>) : JavaFileObject {
    override fun toUri(): URI {
        return entry.value.toURI()
    }
    override fun getName(): String {
        return entry.key
    }
    override fun openInputStream(): InputStream {
        return entry.value.openStream()
    }
    override fun getKind(): JavaFileObject.Kind {
        return JavaFileObject.Kind.CLASS
    }
    override fun toString(): String {
        return "${entry.key} from ${this.javaClass.simpleName}"
    }

    override fun openOutputStream(): OutputStream { throw UnsupportedOperationException() }
    override fun openReader(ignoreEncodingErrors: Boolean): Reader { throw UnsupportedOperationException() }
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence { throw UnsupportedOperationException() }
    override fun openWriter(): Writer { throw UnsupportedOperationException() }
    override fun getLastModified(): Long { throw UnsupportedOperationException() }
    override fun delete(): Boolean { throw UnsupportedOperationException() }
    override fun isNameCompatible(simpleName: String?, kind: JavaFileObject.Kind?): Boolean { throw UnsupportedOperationException() }
    override fun getNestingKind(): NestingKind { throw UnsupportedOperationException() }
    override fun getAccessLevel(): Modifier { throw UnsupportedOperationException() }
}

val getBootClasspathSubresourcesOf = run {
    assert(globalClassLoader != null)

    val resource = globalClassLoader.getResource("java/lang/Object.class")
    val protocol = resource!!.protocol
    assert(protocol.equals("jrt", true))
    val bootClassPathSubresources = ModuleFinder.ofSystem().findAll()

    fun(name: String, recurse: Boolean): Map<String, URL> {
        val results = mutableMapOf<String, URL>()
        bootClassPathSubresources.forEach { moduleReference ->
            moduleReference.open().list().filter { resourceName ->
                resourceName != "module-info.class"
            }.filter { resourceName ->
                resourceName.startsWith(name) && (recurse || resourceName.lastIndexOf('/') == name.length - 1)
            }.forEach {resourceName ->
                assert(!results.containsKey(resourceName))
                results[resourceName] = URL("${moduleReference.location().get()}/$resourceName")
            }
        }
        return results
    }
}

fun getSubresources(name: String, recurse: Boolean): MutableIterable<JavaFileObject> {
    val results = mutableMapOf<String, URL>()
    globalClassLoader.getResources(name).toList().forEach { url ->
        assert(url.protocol.toLowerCase() in listOf("jar", "file"))
        if (url.protocol.equals("jar", true)) {
            val jarURLConnection = url.openConnection() as JarURLConnection
            jarURLConnection.useCaches = false
            if (jarURLConnection.jarEntry.isDirectory) {
                results[name] = url
            } else {
                results.putAll(jarURLConnection.jarFile.entries().toList().filter { jarEntry ->
                    !jarEntry.isDirectory && jarEntry.name.startsWith(name) && (recurse || jarEntry.name.indexOf('/', name.length) == -1)
                }.map {jarEntry ->
                    jarEntry.name to URL("jar", null, "$url!/${ jarEntry.name })")
                }.toMap())
            }
        } else if (url.protocol.equals("file", true)) {
            val file = File(url.file)
            if (file.isFile) {
                results[name] = url
            } else if (file.isDirectory) {
                val namePrefix = if (name.isNotEmpty() && !name.endsWith('/')) {
                    "$name/"
                } else {
                    name
                }
                results.putAll(getFileResources(file, namePrefix, recurse))
            }
        }
    }

    if (results.isEmpty()) {
        results.putAll(getBootClasspathSubresourcesOf(name, recurse))
    }
    return results.filter {
        it.key.endsWith(".class")
    }.map {
        Listing(it)
    }.toMutableList()
}

fun getFileResources(file: File, name: String, recurse: Boolean): Map<String, URL> {
    val results = mutableMapOf<String, URL>()
    file.listFiles().forEach {
        val fileName = "$name${it.name}"
        if (recurse) {
            results.putAll(getFileResources(it, fileName, recurse))
        } else if (file.isFile) {
            results[fileName] = file.toURI().toURL()
        }
    }
    return results
}
