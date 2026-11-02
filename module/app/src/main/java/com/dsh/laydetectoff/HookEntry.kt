package com.dsh.laydetectoff

import android.content.Context
import android.os.Build
import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.query.matchers.FieldMatcher
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Disables only the face-down shortening of PowerManagerService's screen timeout.
 *
 * DexKit selects the host method from its semantic/structural shape. The runtime
 * callback then restores the same timeout-policy result that the method returns
 * when the device is not face-down. Other timeout policy behavior is retained.
 */
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "LayDetectOff"
        private const val MODULE_LIBRARY_DIR = "laydetectoff-native"
        private const val NATIVE_LIBRARY_NAME = "libdexkit.so"

        private val stateLock = Any()
        private val bootstrapped = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
        private val installing = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
        private val installed = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
        private val adjustedLogOnce = AtomicBoolean(false)
        private val policyFailureLogOnce = AtomicBoolean(false)

        @Volatile
        private var moduleApkPath: String? = null

        @Volatile
        private var dexKitLoaded = false

        fun log(message: String) {
            XposedBridge.log("$TAG: $message")
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        moduleApkPath = startupParam.modulePath.takeIf { File(it).isFile }
        if (moduleApkPath == null) {
            log("BLOCKED: initZygote 未提供可读的模块 APK 路径")
        } else {
            log("已从 Xposed StartupParam 取得模块 APK 路径")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Anchors.TARGET_PACKAGE) return
        val hostLoader = lpparam.classLoader ?: run {
            log("BLOCKED: android 包未提供 ClassLoader")
            return
        }

        synchronized(stateLock) {
            if (bootstrapped.containsKey(hostLoader)) return
            bootstrapped[hostLoader] = true
        }

        log("进入 Android 系统进程加载路径（process=${lpparam.processName}, pid=${Process.myPid()}, loader=${hostLoader.javaClass.name}）")
        startSystemContextMonitor(hostLoader)
    }

    /** system_server may receive the package callback before its system Context exists. */
    private fun startSystemContextMonitor(hostLoader: ClassLoader) {
        runCatching {
            Thread({
                var installAttempts = 0
                repeat(120) {
                    if (synchronized(stateLock) { installed.containsKey(hostLoader) }) return@Thread
                    val context = currentSystemContext(hostLoader)
                    if (context != null) {
                        if (installAttempts >= 3) {
                            log("BLOCKED: system Context 已就绪，但 3 次安装均未成功")
                            synchronized(stateLock) { bootstrapped.remove(hostLoader) }
                            return@Thread
                        }
                        installAttempts++
                        if (installTargetHook(hostLoader, context)) return@Thread
                        try {
                            Thread.sleep(750L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    } else {
                        try {
                            Thread.sleep(250L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    }
                }
                log("BLOCKED: 30 秒内未取得 system Context 或 Hook 未安装")
                synchronized(stateLock) { bootstrapped.remove(hostLoader) }
            }, "LayDetectOff-Bootstrap").apply {
                isDaemon = true
                start()
            }
        }.onFailure {
            synchronized(stateLock) { bootstrapped.remove(hostLoader) }
            log("BLOCKED: 无法启动 system Context 监视线程: ${it.message}")
        }
    }

    private fun currentSystemContext(hostLoader: ClassLoader): Context? = runCatching {
        val activityThreadClass = Class.forName(Anchors.ACTIVITY_THREAD_CLASS, false, hostLoader)
        val activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
            ?: return@runCatching null
        XposedHelpers.callMethod(activityThread, "getSystemContext") as? Context
    }.getOrNull()

    private fun installTargetHook(hostLoader: ClassLoader, context: Context): Boolean {
        synchronized(stateLock) {
            if (installed.containsKey(hostLoader)) return true
            if (installing.containsKey(hostLoader)) return false
            installing[hostLoader] = true
        }

        var success = false
        try {
            val apkPath = moduleApkPath?.takeIf { File(it).isFile }
            if (apkPath == null) {
                log("BLOCKED: Xposed StartupParam 未提供有效模块 APK 路径")
                return false
            }
            if (!loadDexKitFromModule(apkPath, context)) return false

            val targetClass = try {
                Class.forName(Anchors.POWER_MANAGER_SERVICE_CLASS, false, hostLoader)
            } catch (error: Throwable) {
                log("BLOCKED: 无法从 android ClassLoader 解析 PowerManagerService: ${error.message}")
                return false
            }
            val targetDexLoader = targetClass.classLoader
            if (targetDexLoader == null) {
                log("BLOCKED: PowerManagerService 由 bootstrap ClassLoader 加载，无法枚举目标 DEX")
                return false
            }
            val baseDexClass = try {
                Class.forName("dalvik.system.BaseDexClassLoader", false, targetDexLoader)
            } catch (error: Throwable) {
                log("BLOCKED: Android runtime 未提供 BaseDexClassLoader: ${error.message}")
                return false
            }
            if (!baseDexClass.isInstance(targetDexLoader)) {
                log("BLOCKED: PowerManagerService defining loader 不是 BaseDexClassLoader: ${targetDexLoader.javaClass.name}")
                return false
            }
            log("PowerManagerService defining loader=${targetDexLoader.javaClass.name}")

            val binding = try {
                DexKitBridge.create(targetDexLoader, true).use { bridge ->
                    val dexCount = bridge.getDexNum()
                    log("DexKit 目标 ClassLoader dexCount=$dexCount")
                    if (dexCount <= 0) {
                        log("BLOCKED: DexKit 未从 PowerManagerService defining loader 读到 DEX")
                        null
                    } else {
                        resolveTarget(bridge, targetDexLoader, targetClass)
                    }
                }
            } catch (error: Throwable) {
                log("DexKit 查询失败（不安装猜测性 Hook）: ${error.javaClass.simpleName}: ${error.message}")
                return false
            } ?: return false

            XposedBridge.hookMethod(binding.targetMethod, makeTimeoutHook(binding))
            success = true
            log(
                "Hook 已安装: ${binding.targetMethod.declaringClass.name}#${binding.targetMethod.name}; " +
                    "faceDown=${binding.faceDownField.name}, policy=${binding.policyMethod.name}"
            )
        } catch (error: Throwable) {
            log("Hook 安装失败: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            synchronized(stateLock) {
                installing.remove(hostLoader)
                if (success) installed[hostLoader] = true
            }
        }
        return success
    }

    /** Read the module APK path from LSPosed's module ClassLoader, not host appInfo. */
    private fun resolveModuleApkPath(moduleLoader: ClassLoader?): String? {
        if (moduleLoader == null) return null

        var current: Class<*>? = moduleLoader.javaClass
        while (current != null) {
            val field = current.declaredFields.firstOrNull {
                it.name == "module" && it.type == String::class.java
            }
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    val value = field.get(moduleLoader) as? String
                    if (value != null && File(value).isFile) return value
                }
            }
            current = current.superclass
        }

        // LSPosed currently exposes module=<APK path> in the loader description.
        val fromDescription = Regex("module=([^,\\]]+)")
            .find(moduleLoader.toString())?.groupValues?.getOrNull(1)
        return fromDescription?.takeIf { File(it).isFile }
    }

    /** Extract the exact DexKit ABI from this module APK into system Context cache. */
    private fun loadDexKitFromModule(moduleApkPath: String, context: Context): Boolean {
        if (dexKitLoaded) return true
        synchronized(stateLock) {
            if (dexKitLoaded) return true

            val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            if (abis.isEmpty()) {
                log("BLOCKED: 当前 system_server ABI 列表为空")
                return false
            }

            val zip = try {
                ZipFile(moduleApkPath)
            } catch (error: Throwable) {
                log("BLOCKED: 无法打开模块 APK: ${error.message}")
                return false
            }

            zip.use { apk ->
                val selected = abis.asSequence()
                    .mapNotNull { abi -> apk.getEntry("lib/$abi/$NATIVE_LIBRARY_NAME")?.let { abi to it } }
                    .firstOrNull()
                if (selected == null) {
                    log("BLOCKED: 模块 APK 不含当前进程 ABI 的 $NATIVE_LIBRARY_NAME")
                    return false
                }

                val (abi, entry) = selected
                val roots = listOf(context.codeCacheDir, context.cacheDir).distinctBy { it.absolutePath }
                for (root in roots) {
                    val directory = File(root, MODULE_LIBRARY_DIR)
                    val probe = runCatching {
                        if (!directory.exists() && !directory.mkdirs()) return@runCatching null
                        File.createTempFile(".write-probe-", ".tmp", directory)
                    }.getOrNull() ?: continue
                    probe.delete()

                    val destination = File(directory, "libdexkit-$abi.so")
                    val temporary = File(directory, "libdexkit-$abi-${Process.myPid()}.tmp")
                    val loaded = runCatching {
                        if (temporary.exists() && !temporary.delete()) error("无法清理旧临时库")
                        apk.getInputStream(entry).use { input ->
                            FileOutputStream(temporary).use { output -> input.copyTo(output) }
                        }
                        if (destination.exists() && !destination.delete()) error("无法替换缓存库")
                        if (!temporary.renameTo(destination)) error("临时库原子改名失败")
                        System.load(destination.absolutePath)
                        true
                    }.getOrElse {
                        log("DexKit so 加载失败（$abi, ${root.absolutePath}）: ${it.message}")
                        temporary.delete()
                        false
                    }
                    if (loaded) {
                        dexKitLoaded = true
                        log("DexKit native 已从模块 APK 加载（ABI=$abi）")
                        return true
                    }
                }
            }

            log("BLOCKED: system Context cache 不可写，DexKit native 未加载")
            return false
        }
    }

    /**
     * Unique static contract:
     * (long,long)->long, uses the one-day guard, and invokes Math.min(long,long).
     * Candidate and every required relation are checked before a runtime hook exists.
     */
    private fun resolveTarget(
        bridge: DexKitBridge,
        hostLoader: ClassLoader,
        targetClass: Class<*>,
    ): HookBinding? {
        val candidates = bridge.findMethod {
            findFirst = false
            searchPackages(Anchors.POWER_SERVICE_PACKAGE_PREFIX)
            matcher {
                declaredClass(Anchors.POWER_MANAGER_SERVICE_CLASS)
                returnType("long")
                paramTypes("long", "long")
                addUsingNumber(Anchors.DAY_TIMEOUT_GUARD_MS)
                addUsingField(FieldMatcher().type("boolean"), UsingType.Read)
                addUsingField(FieldMatcher().type("int"), UsingType.Read)
                addInvoke(Anchors.MATH_MIN_LONG_DESCRIPTOR)
            }
        }.toList()

        log("DexKit 查询=face-down timeout override，候选数=${candidates.size}")
        val candidate = candidates.singleOrNull() ?: run {
            log("BLOCKED: 语义候选不是唯一项；本轮不安装 Hook")
            return null
        }
        if (candidate.declaredClassName != Anchors.POWER_MANAGER_SERVICE_CLASS) {
            log("BLOCKED: 唯一候选不属于 PowerManagerService: ${candidate.descriptor}")
            return null
        }
        if (candidate.returnTypeName != "long" || candidate.paramTypeNames != listOf("long", "long")) {
            log("BLOCKED: 唯一候选的签名与已验证结构不符: ${candidate.descriptor}")
            return null
        }
        if (candidate.invokes.none { it.descriptor == Anchors.MATH_MIN_LONG_DESCRIPTOR }) {
            log("BLOCKED: 候选缺少 Math.min(long,long) 关系: ${candidate.descriptor}")
            return null
        }

        val readFields = candidate.usingFields
            .filter { it.usingType.isRead() }
            .map { it.field }
            .filter { it.declaredClassName == candidate.declaredClassName }
            .distinctBy { it.descriptor }
        val faceDownData = readFields.filter {
            it.descriptor.endsWith(":Z") && !Modifier.isStatic(it.modifiers)
        }.singleOrNull() ?: run {
            log("BLOCKED: 候选未唯一读到自身 boolean 状态字段: ${candidate.descriptor}")
            return null
        }
        val wakeLockSummaryData = readFields.filter {
            it.descriptor.endsWith(":I") && !Modifier.isStatic(it.modifiers)
        }.singleOrNull() ?: run {
            log("BLOCKED: 候选未唯一读到自身 int 策略输入字段: ${candidate.descriptor}")
            return null
        }

        val policyMethods = candidate.invokes.filter {
            it.returnTypeName == "long" && it.paramTypeNames == listOf("int", "long") &&
                !Modifier.isStatic(it.modifiers)
        }.distinctBy { it.descriptor }
        val policyData = policyMethods.singleOrNull() ?: run {
            log("BLOCKED: 候选未唯一引用 (int,long)->long 的实例策略方法")
            return null
        }
        val policyFieldData = readFields.filter {
            it.typeName == policyData.declaredClassName && !Modifier.isStatic(it.modifiers)
        }.singleOrNull() ?: run {
            log("BLOCKED: 未找到与策略方法声明类对应的唯一实例字段")
            return null
        }

        return try {
            val targetMethod = candidate.getMethodInstance(targetClass.classLoader ?: hostLoader)
            val faceDownField = faceDownData.getFieldInstance(targetClass.classLoader ?: hostLoader)
            val wakeLockSummaryField = wakeLockSummaryData.getFieldInstance(targetClass.classLoader ?: hostLoader)
            val policyField = policyFieldData.getFieldInstance(targetClass.classLoader ?: hostLoader)
            val policyMethod = policyData.getMethodInstance(targetClass.classLoader ?: hostLoader)

            require(targetMethod.declaringClass === targetClass)
            require(targetMethod.declaringClass.name == candidate.declaredClassName)
            require(targetMethod.returnType == Long::class.javaPrimitiveType)
            require(targetMethod.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)))
            require(!Modifier.isStatic(targetMethod.modifiers))
            require(faceDownField.declaringClass == targetMethod.declaringClass && faceDownField.type == Boolean::class.javaPrimitiveType)
            require(wakeLockSummaryField.declaringClass == targetMethod.declaringClass && wakeLockSummaryField.type == Int::class.javaPrimitiveType)
            require(policyField.declaringClass == targetMethod.declaringClass)
            require(policyField.type.name == policyMethod.declaringClass.name)
            require(policyMethod.returnType == Long::class.javaPrimitiveType)
            require(policyMethod.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)))
            require(!Modifier.isStatic(policyMethod.modifiers))

            listOf(faceDownField, wakeLockSummaryField, policyField, policyMethod).forEach {
                it.isAccessible = true
            }
            log(
                "唯一候选通过关系核验: ${candidate.descriptor}; " +
                    "bool=${faceDownData.descriptor}; int=${wakeLockSummaryData.descriptor}; " +
                    "policy=${policyData.descriptor}"
            )
            HookBinding(targetMethod, faceDownField, wakeLockSummaryField, policyField, policyMethod)
        } catch (error: Throwable) {
            log("BLOCKED: DexKit 成员无法映射到宿主反射对象: ${error.message}")
            null
        }
    }

    private fun makeTimeoutHook(binding: HookBinding): XC_MethodHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.hasThrowable()) return
            val owner = param.thisObject ?: return
            val faceDown = runCatching { binding.faceDownField.getBoolean(owner) }.getOrElse {
                logPolicyFailureOnce("读取 face-down 状态失败: ${it.message}")
                return
            }
            if (!faceDown) return

            val normalTimeout = param.args.getOrNull(0) as? Long ?: return
            // The original method deliberately bypasses the policy override for its 24-hour sentinel.
            if (normalTimeout == Anchors.DAY_TIMEOUT_GUARD_MS) return

            val restoredTimeout = runCatching {
                val policy = binding.policyField.get(owner)
                if (policy == null) {
                    normalTimeout
                } else {
                    val wakeLockSummary = binding.wakeLockSummaryField.getInt(owner)
                    binding.policyMethod.invoke(policy, wakeLockSummary, normalTimeout) as? Long
                        ?: error("策略方法未返回 long")
                }
            }.getOrElse {
                logPolicyFailureOnce("重算超时策略失败，先移除 face-down 短超时以避免立即息屏: ${it.message}")
                normalTimeout
            }

            param.result = restoredTimeout
            if (adjustedLogOnce.compareAndSet(false, true)) {
                log("静态 Hook 分支已执行：face-down clamp 被移除，策略超时=$restoredTimeout ms")
            }
        }
    }

    private fun logPolicyFailureOnce(message: String) {
        if (policyFailureLogOnce.compareAndSet(false, true)) log("BLOCKED: $message")
    }

    private data class HookBinding(
        val targetMethod: Method,
        val faceDownField: Field,
        val wakeLockSummaryField: Field,
        val policyField: Field,
        val policyMethod: Method,
    )
}
