@file:Suppress("UNCHECKED_CAST")

package taboolib.module.nms

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.tabooproject.reflex.Reflex.Companion.getProperty
import taboolib.common.Inject
import taboolib.common.io.runningClassMapWithoutLibrary
import taboolib.common.io.runningClassesNames
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.util.unsafeLazy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val nmsProxyClassMap = ConcurrentHashMap<String, Class<*>>()

private val nmsProxyInstanceMap = ConcurrentHashMap<String, Any>()

private val packetPool = ConcurrentHashMap<String, ExecutorService>()

/**
 * 是否为 universal obc 版本（paper 1.10.5+）
 */
val isUniversalCraftBukkit by unsafeLazy {
    runCatching { Class.forName("org.bukkit.craftbukkit.CraftServer") }.getOrNull() != null
}

/**
 * 获取 MinecraftServer 实例
 */
val minecraftServerObject: Any by unsafeLazy {
    Bukkit.getServer().getProperty(
        when (MinecraftVersion.major) {
            // 1.8, 1.9, 1.10, 1.11, 1.12, 1.13 类型为：MinecraftServer
            in MinecraftVersion.V1_8..MinecraftVersion.V1_13 -> "console"
            // 1.14, 1.15, 1.16, 1.17, 1.18, 1.19, 1.20 类型为：DedicatedServer
            in MinecraftVersion.V1_14..MinecraftVersion.V1_20 -> "console"
            // 其他版本
            else -> "console"
        }
    )!!
}

/**
 * 获取 OBC 类
 */
fun obcClass(name: String): Class<*> {
    return if (isUniversalCraftBukkit) {
        Class.forName("org.bukkit.craftbukkit.$name")
    } else {
        Class.forName("org.bukkit.craftbukkit.${MinecraftVersion.minecraftVersion}.$name")
    }
}

/**
 * 获取 NMS 类
 */
fun nmsClass(name: String): Class<*> {
    return if (MinecraftVersion.isUniversal) {
        Class.forName(MinecraftVersion.mapping.classMap[name]?.replace('/', '.') ?: throw ClassNotFoundException(name))
    } else {
        Class.forName("net.minecraft.server.${MinecraftVersion.minecraftVersion}.$name")
    }
}

/**
 * 禁用数据包监听器
 */
fun disablePacketListener() {
    ProtocolHandler.disable()
}

@Synchronized
fun <T> nmsProxy(clazz: Class<T>, bind: String = "{name}Impl", vararg parameter: Any): T {
    return nmsProxy(clazz, bind, emptyList(), *parameter)
}

@Synchronized
fun <T> nmsProxy(clazz: Class<T>, bind: String = "{name}Impl", parent: List<String> = emptyList(), vararg parameter: Any): T {
    val key = "${clazz.name}:$bind:${parameter.joinToString(",") { it.javaClass.name.toString() }}"
    // 从缓存中获取
    if (nmsProxyInstanceMap.containsKey(key)) {
        return nmsProxyInstanceMap[key] as T
    }
    // 获取代理类并实例化
    val newInstance = nmsProxyClass(clazz, bind, parent).getDeclaredConstructor(*parameter.map { it.javaClass }.toTypedArray()).newInstance(*parameter)
    // 缓存实例
    nmsProxyInstanceMap[key] = newInstance!!
    return newInstance
}

inline fun <reified T> nmsProxy(bind: String = "{name}Impl", vararg parameter: Any): T {
    return nmsProxy(T::class.java, bind, emptyList(), *parameter)
}

inline fun <reified T> nmsProxy(bind: String = "{name}Impl", parent: List<String> = emptyList(), vararg parameter: Any): T {
    return nmsProxy(T::class.java, bind, parent, *parameter)
}

@Synchronized
fun <T> nmsProxyClass(clazz: Class<T>, bind: String = "{name}Impl", parent: List<String> = emptyList()): Class<T> {
    parent.forEach { nmsProxyClass(clazz, it) }
    return nmsProxyClass(clazz, bind)
}

@Synchronized
fun <T> nmsProxyClass(clazz: Class<T>, bind: String = "{name}Impl"): Class<T> {
    val key = "${clazz.name}:$bind"
    // 从缓存中获取
    if (nmsProxyClassMap.containsKey(key)) {
        return nmsProxyClassMap[key] as Class<T>
    }
    // 生成代理类
    val bindClass = bind.replace("{name}", clazz.name)
    val newClass = AsmClassTransfer(bindClass).createNewClass()
    // 同时生成所有的内部类
    runningClassesNames.filter { name -> name.startsWith("$bindClass\$") }.forEach { name -> AsmClassTransfer(name).createNewClass() }
    // 缓存代理类
    nmsProxyClassMap[key] = newClass
    return newClass as Class<T>
}

inline fun <reified T> nmsProxyClass(bind: String = "{name}Impl", parent: List<String> = emptyList()): Class<T> {
    return nmsProxyClass(T::class.java, bind, parent)
}

/**
 * 向玩家发送打包数据包（异步，1.19.4+）
 */
fun Player.sendBundlePacket(vararg packet: Any): CompletableFuture<Void> {
    return sendBundlePacket(packet.toList())
}

/**
 * 向玩家发送打包数据包（异步，1.19.4+）
 */
fun Player.sendBundlePacket(packet: List<Any>): CompletableFuture<Void> {
    if (MinecraftVersion.isBundlePacketSupported) {
        val bundlePacket = PacketSender.createBundlePacket(packet)
        if (bundlePacket != null) {
            return sendPacket(bundlePacket)
        }
    }
    return CompletableFuture.allOf(*packet.map { sendPacket(it) }.toTypedArray())
}

/**
 * 向玩家发送数据包（异步）
 */
fun Player.sendPacket(packet: Any): CompletableFuture<Void> {
    val future = CompletableFuture<Void>()
    val pool = packetPool.computeIfAbsent(name) { Executors.newSingleThreadExecutor() }
    pool.submit {
        try {
            sendPacketBlocking(packet)
            future.complete(null)
        } catch (e: Throwable) {
            future.completeExceptionally(e)
            e.printStackTrace()
        }
    }
    return future
}

/**
 * 向玩家发送打包数据包（1.19.4+）
 */
fun Player.sendBundlePacketBlocking(vararg packet: Any) {
    sendBundlePacketBlocking(packet.toList())
}

/**
 * 向玩家发送打包数据包（1.19.4+）
 */
fun Player.sendBundlePacketBlocking(packet: List<Any>) {
    if (MinecraftVersion.isBundlePacketSupported) {
        val bundlePacket = PacketSender.createBundlePacket(packet)
        if (bundlePacket != null) {
            sendPacketBlocking(bundlePacket)
            return
        }
    }
    packet.forEach { sendPacketBlocking(it) }
}

/**
 * 向玩家发送数据包
 */
fun Player.sendPacketBlocking(packet: Any) {
    PacketSender.sendPacket(this, packet)
}

/**
 * 监听器
 */
@Inject
@PlatformSide(Platform.BUKKIT)
private object PoolListener {

    @SubscribeEvent
    private fun onJoin(e: PlayerJoinEvent) {
        packetPool.computeIfAbsent(e.player.name) { Executors.newSingleThreadExecutor() }
    }

    @SubscribeEvent
    private fun onQuit(e: PlayerQuitEvent) {
        packetPool.remove(e.player.name)?.shutdownNow()
    }
}