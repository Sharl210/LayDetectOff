package com.dsh.laydetectoff

/**
 * DexKit 查询所需的稳定语义锚点。
 *
 * 目标方法名、宿主字段名及辅助策略方法名都不作为生产定位条件；
 * 由目标包、签名、24 小时守卫常量、Math.min 调用和字段/调用关系共同确认。
 */
object Anchors {
    /** PowerManagerService 所在的 system_server 包作用域。 */
    const val TARGET_PACKAGE = "android"

    /** Android system_server 电源服务类型锚点；成员仍必须由 DexKit 结构唯一解析。 */
    const val POWER_MANAGER_SERVICE_CLASS = "com.android.server.power.PowerManagerService"

    /** 电源服务稳定包前缀，用于缩小 DexKit 候选域。 */
    const val POWER_SERVICE_PACKAGE_PREFIX = "com.android.server.power"

    /** 24 小时特殊守卫；候选方法必须实际引用该 long 常量。 */
    const val DAY_TIMEOUT_GUARD_MS = 86_400_000L

    /** 候选方法中用于压短屏幕超时的标准 Java 调用。 */
    const val MATH_MIN_LONG_DESCRIPTOR = "Ljava/lang/Math;->min(JJ)J"

    /** Xposed bootstrap 所需的 Android 系统包名。 */
    const val ACTIVITY_THREAD_CLASS = "android.app.ActivityThread"
}
