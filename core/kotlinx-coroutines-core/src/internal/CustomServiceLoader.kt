package kotlinx.coroutines.internal

import java.io.*
import java.lang.ClassCastException
import java.net.URL
import java.util.*
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.io.IOException
import java.io.BufferedReader

/**
 * Simplified version of [ServiceLoader]
 *
 * CustomServiceLoader locates and instantiates all service providers named in configuration files placed in the resource directory <tt>META-INF/services</tt>.
 * In order to speed up reading JAR resources it omits signed JAR verification.
 * In case of [ServiceConfigurationError] thrown service loading falls back to the standard [ServiceLoader].
 */
private const val PREFIX: String = "META-INF/services/"

internal object CustomServiceLoader {

    fun <S> load(service: Class<S>, loader: ClassLoader): List<S> {
        return try {
            loadProviders(service, loader)
        } catch (e: ServiceConfigurationError) {
            ServiceLoader.load(service, loader).toList()
        }
    }

    private fun <S> loadProviders(service: Class<S>, loader: ClassLoader): List<S> {
        val urls = try {
            val fullServiceName = PREFIX + service.name
            loader.getResources(fullServiceName).toList()
        } catch (e: IOException) {
            error(service, "Error locating configuration files", e)
        }
        val providers = mutableListOf<S>()
        urls.forEach {
            val providerName = parse(service, it)
            if (providerName.isNotEmpty()) {
                providers.add(getProviderInstance(providerName, loader, service))
            }
        }
        return providers
    }

    private fun <S> getProviderInstance(name: String, loader: ClassLoader, service: Class<S>): S {
        val cl = try {
            Class.forName(name, false, loader)
        } catch (e: ClassNotFoundException) {
            error(service, "Provider $name not found", e)
        }
        if (!service.isAssignableFrom(cl)) {
            error(
                service, "Provider $name  not a subtype",
                ClassCastException("${service.canonicalName} is not assignable from ${cl.canonicalName}")
            )
        }
        return try {
            service.cast(cl.getDeclaredConstructor().newInstance())
        } catch (e: Throwable) {
            error(service, "Provider $name could not be instantiated", e)
        }
    }

    private fun <S> parse(service: Class<S>, url: URL): String {
        try {
            val string = url.toString()
            // the syntax of JAR URL is: jar:<url>!/{entry}
            val separatorIndex = string.indexOf('!')
            val pathToJar = string.substring(0, separatorIndex)
            val entry = string.substring(separatorIndex + 2, string.length)
            (JarFile(pathToJar.substring("jar:file:/".length), false) as Closeable).use { file ->
                BufferedReader(InputStreamReader((file as JarFile).getInputStream(ZipEntry(entry)), "UTF-8")).use { r ->
                    return parseFile(service, r)
                }
            }
        } catch (e: Throwable) {
            error(service, "Error reading configuration file", e)
        }
    }

    private fun parseFile(service: Class<*>, r: BufferedReader): String {
        var line = ""
        while (line.isEmpty()) {
            line = r.readLine()
            val commentPos = line.indexOf('#')
            if (commentPos >= 0) line = line.substring(0, commentPos)
            line = line.trim()
            if (!line.isEmpty()) {
                try {
                    require(line.all { it == '.' || Character.isJavaIdentifierPart(it) })
                } catch (e: IllegalArgumentException) {
                    error(service, "Illegal configuration-file syntax", e)
                }
            }
        }
        return line
    }

    private fun error(service: Class<*>, msg: String, e: Throwable): Nothing =
        throw ServiceConfigurationError(service.name + ": " + msg, e)
}