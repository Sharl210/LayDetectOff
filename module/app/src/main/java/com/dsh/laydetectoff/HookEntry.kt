package com.dsh.laydetectoff

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 平放检测关闭模块（realme UI / ColorOS「倾斜·平放」TP 节点链路）。
 *
 * ============ 拦截策略（三层，由粗到细） ============
 *
 *  L1 断源：让 registerRecliningModeSensor() 直接 return，使 lay_detect 传感器根本不被注册。
 *           全机只有本进程持有该传感器，不注册 = 整条链路从源头死掉。
 *           这是首选，代价最小，且不影响同类里的陀螺仪校准。
 *
 *  L2 兜底：若传感器已被注册（例如模块在注册之后才加载），则拦 writeNodeFileByDevice，
 *           只吞 nodeFlag == 171 的写入，其余节点放行。
 *
 *  L3 语义：用 DexKit 在宿主 APK 里按结构找回 L1/L2 的目标，覆盖改名/重构的新版本。
 *
 * 三层都失败就不装任何 Hook —— 绝不退回「见方法就 hook」的兜底。
 *
 * ============ 为什么不拦 onSensorChanged ============
 * 同一个 onSensorChanged 里还有陀螺仪校准（type==4）与 AMD 传感器分支，
 * 整段吞掉会破坏校准状态机，症状是手势功能整体异常。必须拦在更窄的位置。
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "LayDetectOff"

        private val installed = Collections.synchronizedMap(
            WeakHashMap<ClassLoader, Boolean>(),
        )

        /** 累计拦截次数，供用户确认模块是否真的生效。 */
        @Volatile
        var blockCount: Int = 0
            private set

        /** 最近一次拦截的说明，便于对照日志。 */
        @Volatile
        var lastEvent: String? = null
            private set

        /** 探针结论：宿主固件是否支持 171 号节点。null = 尚未观测到。 */
        @Volatile
        var node171Supported: Boolean? = null
            private set

        fun log(msg: String) {
            XposedBridge.log("$TAG: $msg")
        }

        private fun record(what: String) {
            blockCount++
            lastEvent = what
            log("[$what] 已拦截（累计 $blockCount 次）")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Anchors.TARGET_PACKAGE) return
        log("进入目标进程 ${lpparam.packageName}（pid=${android.os.Process.myPid()}）")

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    val ctx = param.args.firstOrNull() as? Context ?: return
                    install(app, ctx)
                }
            },
        )
    }

    private fun install(app: Application, ctx: Context) {
        val loader = ctx.classLoader ?: app.javaClass.classLoader ?: return
        synchronized(installed) {
            if (installed.containsKey(loader)) return
            installed[loader] = true
        }

        installProbe(loader)                    // 只读探针：报告 171 节点是否被固件支持

        var n = 0
        n += hookByName(loader)                 // L1 + L2 名字定位（快）
        n += hookByDexKit(app.applicationInfo.sourceDir, loader)  // L3 语义定位（跨版本）

        if (n == 0) {
            log("所有定位路径均未命中，本轮不安装任何 Hook")
        } else {
            log("安装完成，共 $n 个 Hook 点")
            // 复位残留：若模块加载前 TP 已被写成平放档位，断源不会把已下发的值收回来。
            // 这里主动把 171 节点写回 0（非平放），确保接管后处于干净状态。
            resetRecliningNode(loader)
        }
    }

    /**
     * 把 TP 的 171 节点复位为 0。
     * 只在安装成功后执行一次；任何异常都吞掉并记录，绝不影响主流程。
     */
    private fun resetRecliningNode(loader: ClassLoader) {
        runCatching {
            val cls = Class.forName(Anchors.TOUCH_NODE_MANAGER, false, loader)
            val instance = XposedHelpers.callStaticMethod(cls, "getInstance")
                ?: return@runCatching
            val method = cls.declaredMethods.firstOrNull { isTouchNodeWriter(it) }
                ?: return@runCatching
            method.isAccessible = true
            // 双屏设备分别对应 device 0 / 1；单屏设备对 1 的调用会被宿主自身判空，安全。
            for (device in intArrayOf(0, 1)) {
                runCatching {
                    method.invoke(instance, device, Anchors.TP_RECLINING_MODE_NODE, "0")
                }.onFailure { log("复位节点 device=$device 失败（可忽略）：${it.message}") }
            }
            log("已尝试把 TP 节点 171 复位为 0")
        }.onFailure { log("复位残留状态失败（可忽略）：${it.message}") }
    }

    /**
     * 只读探针：拦截 isTouchNodeSupport(dId, nodeFlag)，把「171 号节点到底支不支持」记下来。
     *
     * 为什么需要它：
     *   本机 /proc/touchpanel/ 下没有 171 对应的节点文件，只能靠宿主自己的判定来确认。
     *   若 171 不被支持，writeNodeForReclining 实际上是空转，
     *   那么「平放导致异常」就不是这条链路造成的 —— 必须换方向查，不能假装成功。
     *   探针只记录返回值，不改任何行为。
     */
    private fun installProbe(loader: ClassLoader): Int {
        val cls = runCatching {
            Class.forName(Anchors.TOUCH_NODE_MANAGER, false, loader)
        }.getOrNull() ?: return 0

        val m = cls.declaredMethods.firstOrNull {
            it.name == "isTouchNodeSupport" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return 0

        return runCatching {
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val node = param.args.getOrNull(1) as? Int ?: return
                    if (node != Anchors.TP_RECLINING_MODE_NODE) return
                    val dId = param.args.getOrNull(0)
                    val supported = param.result as? Boolean ?: false
                    log("探针：isTouchNodeSupport(dId=$dId, node=171) = $supported")
                    node171Supported = supported
                }
            })
            1
        }.getOrElse {
            log("探针安装失败（可忽略）：${it.message}")
            0
        }
    }

    // -----------------------------------------------------------------
    // L1 + L2：按类名 + 签名定位
    // -----------------------------------------------------------------

    private fun hookByName(loader: ClassLoader): Int {
        val engine = runCatching {
            Class.forName(Anchors.ENGINE_SERVICE_CLASS, false, loader)
        }.getOrNull()

        var n = 0

        if (engine != null) {
            // L1：注册方法 -> 直接 return
            for (m in engine.declaredMethods) {
                if (m.name != Anchors.REGISTER_SENSOR_METHOD) continue
                if (m.parameterTypes.isNotEmpty()) continue
                if (m.returnType != Void.TYPE) continue
                runCatching {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            record("L1-阻止注册lay_detect")
                            param.result = null
                        }
                    })
                    n++
                    log("L1 已 Hook ${engine.name}#${m.name}")
                }.onFailure { log("L1 Hook 失败：${it.message}") }
            }

            // L2a：姿态写入方法 -> 直接 return
            for (m in engine.declaredMethods) {
                if (m.name != Anchors.WRITE_NODE_METHOD) continue
                if (m.parameterTypes.size != 1) continue
                if (m.parameterTypes[0] != Float::class.javaPrimitiveType) continue
                runCatching {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            record("L2a-阻止姿态写入 value=${param.args.getOrNull(0)}")
                            param.result = null
                        }
                    })
                    n++
                    log("L2a 已 Hook ${engine.name}#${m.name}")
                }.onFailure { log("L2a Hook 失败：${it.message}") }
            }
        } else {
            log("未找到 ${Anchors.ENGINE_SERVICE_CLASS}，交由 DexKit 处理")
        }

        // L2b：触控节点写入 -> 只吞 171
        val touch = runCatching {
            Class.forName(Anchors.TOUCH_NODE_MANAGER, false, loader)
        }.getOrNull()

        if (touch != null) {
            for (m in touch.declaredMethods) {
                if (!isTouchNodeWriter(m)) continue
                runCatching {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val node = param.args.getOrNull(1) as? Int ?: return
                            if (node != Anchors.TP_RECLINING_MODE_NODE) return
                            record("L2b-阻止写入TP节点171 value=${param.args.getOrNull(2)}")
                            param.result = false
                        }
                    })
                    n++
                    log("L2b 已 Hook ${touch.name}#${m.name}")
                }.onFailure { log("L2b Hook 失败：${it.message}") }
            }
        }

        return n
    }

    /** 触控节点写入方法判据：名字 + (int,int,String) + boolean 返回。 */
    private fun isTouchNodeWriter(m: Method): Boolean {
        if (m.name != Anchors.TOUCH_WRITE_METHOD) return false
        val p = m.parameterTypes
        if (p.size != 3) return false
        if (p[0] != Int::class.javaPrimitiveType) return false
        if (p[1] != Int::class.javaPrimitiveType) return false
        if (p[2] != String::class.java) return false
        return m.returnType == Boolean::class.javaPrimitiveType
    }

    // -----------------------------------------------------------------
    // L3：DexKit 语义定位（跨版本通杀）
    // -----------------------------------------------------------------

    private fun hookByDexKit(apkPath: String, loader: ClassLoader): Int {
        return runCatching {
            DexKitBridge.create(apkPath).use { bridge ->
                var n = 0

                // 查询 1：注册平放传感器的方法。
                // 语义 = 无参 void 方法，且内部调用了 getDefaultSensor 并用到 33171082 这个类型号。
                // 注意 paramCount(0) 才表示「无参数」；paramTypes() 无参版本是清空约束，不是取无参。
                val registerCandidates = query(bridge, "注册平放传感器") {
                    matcher {
                        returnType("void")
                        paramCount(0)
                        addInvoke(Anchors.GET_DEFAULT_SENSOR_DESC)
                        usingNumbers(Anchors.RECLINING_MODE_SENSOR_TYPE)
                    }
                }
                n += hookAll(registerCandidates, loader, "L3-注册", Mode.REGISTER)

                // 查询 2：姿态写入方法。
                // 语义 = (float)void，且内部调用了触控节点写入。
                if (n == 0) {
                    val writeCandidates = query(bridge, "姿态写入") {
                        matcher {
                            returnType("void")
                            paramTypes("float")
                            addInvoke {
                                name(Anchors.TOUCH_WRITE_METHOD)
                            }
                        }
                    }
                    n += hookAll(writeCandidates, loader, "L3-姿态写入", Mode.SWALLOW)
                }

                n
            }
        }.getOrElse { e ->
            log("DexKit 路径异常：${e.message}")
            0
        }
    }

    private enum class Mode { REGISTER, SWALLOW }

    private fun query(
        bridge: DexKitBridge,
        label: String,
        init: FindMethod.() -> Unit,
    ): List<MethodData> {
        return runCatching { bridge.findMethod(init) }
            .onFailure { log("$label 查询异常：${it.message}") }
            .getOrDefault(emptyList())
            .also { log("$label 候选数=${it.size}") }
    }

    private fun hookAll(
        candidates: List<MethodData>,
        loader: ClassLoader,
        label: String,
        mode: Mode,
    ): Int {
        var n = 0
        for (data in candidates) {
            val method = runCatching { data.getMethodInstance(loader) }.getOrNull() ?: continue
            // 只接受确实属于宿主 ClassLoader 的方法，避免误挂到模块自身。
            if (method.declaringClass.classLoader !== loader) continue

            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        record(label)
                        param.result = null
                    }
                })
                n++
                log("[$label] 已 Hook ${data.descriptor}")
            }.onFailure { log("[$label] Hook 失败 ${data.descriptor}: ${it.message}") }
        }
        return n
    }
}
