package taboolib.common.io

import javassist.bytecode.ClassFile
import org.reflections.Reflections
import org.reflections.scanners.Scanner
import org.reflections.scanners.Scanners
import org.reflections.util.ConfigurationBuilder
import org.reflections.util.JavassistHelper
import org.reflections.util.NameHelper
import taboolib.common.ClassAppender
import taboolib.common.PrimitiveIO
import taboolib.common.util.unsafeLazy

private open class TabooLibScanner(val name: String) : Scanner, NameHelper {

    override fun index(): String = name

    private val subTypes = mutableSetOf<String>()
    private val implements = mutableSetOf<String>()
    private val typeAnnotations = mutableSetOf<String>()
    private val methodAnnotations = mutableSetOf<String>()
    private val fieldAnnotations = mutableSetOf<String>()

    fun subtype(vararg a: String) {
        subTypes += a.map { "$taboolibPath.$it" }
    }

    fun implements(vararg a: String) {
        implements += a.map { "$taboolibPath.$it" }
    }

    fun typeAnnotation(vararg a: String) {
        typeAnnotations += a.map { "$taboolibPath.$it" }
    }

    fun methodAnnotation(vararg a: String) {
        methodAnnotations += a.map { "$taboolibPath.$it" }
    }

    fun fieldAnnotation(vararg a: String) {
        fieldAnnotations += a.map { "$taboolibPath.$it" }
    }

    override fun scan(classFile: ClassFile): MutableList<MutableMap.MutableEntry<String, String>>? {
        if (subTypes.any { it == classFile.superclass || classFile.interfaces?.any { i -> it == i } == true }) {
            return entries("@$name:subtype", classFile.name)
        }
        if (classFile.interfaces.any { it in implements }) {
            return entries("@$name:implements", classFile.name)
        }
        if (JavassistHelper.getAnnotations(classFile::getAttribute).any { it in typeAnnotations }) {
            return entries("@$name:type-annotation", classFile.name)
        }
        classFile.methods?.forEach { method ->
            if (JavassistHelper.getAnnotations(method::getAttribute).any { it in methodAnnotations }) {
                return entries("@$name:method-annotation", classFile.name)
            }
        }
        classFile.fields?.forEach { field ->
            if (JavassistHelper.getAnnotations(field::getAttribute).any { it in fieldAnnotations }) {
                return entries("@$name:field-annotation", classFile.name)
            }
        }
        return null
    }
}

private object VisitorHandlerScanner : TabooLibScanner("visitor-handler") {
    init {
        subtype(
            "common.platform.Plugin",
            "expansion.CustomType"
        )
        implements(
            "platform.compat.PlaceholderExpansion"
        )
        typeAnnotation(
            "common.platform.Awake",
            "common.platform.PlatformImplementation",
            "common.env.RuntimeResource",
            "common.env.RuntimeResources",
            "common.env.RuntimeDependency",
            "common.env.RuntimeDependencies",
        )
        methodAnnotation(
            "common.platform.command.CommandHeader",
            "module.configuration.Config",
            "common.platform.Awake",
            "common.platform.Schedule",
            "common.platform.event.SubscribeEvent",
            "module.kether.KetherParser",
            "module.kether.KetherProperty"
        )
        fieldAnnotation(
            "module.configuration.Config",
            "module.configuration.ConfigNode",
            "common.platform.command.CommandBody"
        )
    }
}

val runningClassesNames: List<String> get() = taboolibReflections.getAll(Scanners.SubTypes).toList()

val taboolibReflections: Reflections by lazy {
    Reflections(
        ConfigurationBuilder()
            .setScanners(
                VisitorHandlerScanner,
                *Scanners.values()
            )
            .forPackage(groupId)
            .addClassLoaders(ClassAppender.getClassLoader())
            .setParallel(true)
    )
}

val visitorHandlerAwareClasses: List<Class<*>> by unsafeLazy {
    val cl = ClassAppender.getClassLoader()
    taboolibReflections.getAll(VisitorHandlerScanner).mapNotNull {
        if (it.startsWith("@")) null else
            VisitorHandlerScanner.forClass(it, cl) ?: run {
                PrimitiveIO.warn("(visitor handler) Failed to load class $it")
                null
            }
    }
}
