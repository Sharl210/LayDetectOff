package com.dsh.laydetectoff

/**
 * 语义锚点集中定义，便于跨版本审计。
 *
 * ===== 本机取证确认的完整链路 =====
 *
 *   lay_detect（qti.sensor.lay_detect, type=33171082）— 系统里唯一的平放/倾斜姿态传感器
 *     ↓  SensorEventListener.onSensorChanged(SensorEvent)
 *   OplusGestureEngineService.AnonymousClass2.onSensorChanged()
 *     ↓  判定 getType() == 33171082
 *   writeNodeForReclining(values[0])
 *     ↓
 *   OplusTouchNodeManager.writeNodeFileByDevice(dId, 171, String.valueOf((int) v))
 *     ↓
 *   触控 IC 的 171 号「倾斜/平放模式」节点
 *
 * 关键事实 —— 决定现象为什么「有时有、有时没有」：
 *   1. 该传感器只在 SCREEN_ON 时注册、SCREEN_OFF 时注销（见类内 SCREEN_ON/SCREEN_OFF 广播接收器）；
 *   2. values[0] 在 0 / 1 / 2 之间跳变，写入固件的档位随之改变；
 *   3. 全机只有 com.oplus.gesture 一条连接持有该传感器（sensorservice 实测 connections=1）。
 *
 * 已排除、不处理的相似路径：
 *   ScreenOffGestureService 的「抬起唤醒」倾角传感器：值 1 → 延迟 500ms → goToSleep。
 *   其开关 oplus_customize_gesture_wake_up_arouse 在本机为 0，且监听器未注册，不是本次对象。
 */
object Anchors {

    /** 目标进程：厂商手势引擎，本机 lay_detect 的唯一持有者（pid 8973 实测）。 */
    const val TARGET_PACKAGE = "com.oplus.gesture"

    /** 承载姿态写入逻辑的服务类。跨版本由 DexKit 兜底，不依赖此名。 */
    const val ENGINE_SERVICE_CLASS = "com.oplus.gesture.phonegesture.gestureservice.OplusGestureEngineService"

    /**
     * lay_detect 的传感器类型号（qti.sensor.lay_detect）。
     * 内核/传感器 HAL 上报，设备级稳定常量，不随 APK 混淆变化 —— 首选语义锚点。
     */
    const val RECLINING_MODE_SENSOR_TYPE = 33171082

    /** 写入触控 IC 的「倾斜/平放模式」节点号。 */
    const val TP_RECLINING_MODE_NODE = 171

    /** 触控节点管理器（应用内为桩，真实实现走 binder 到 system_server）。 */
    const val TOUCH_NODE_MANAGER = "com.oplus.touchnode.OplusTouchNodeManager"

    /** 写节点的方法名（带 deviceId 的重载）。 */
    const val TOUCH_WRITE_METHOD = "writeNodeFileByDevice"

    /** 注册平放传感器的方法名（主拦截点，次选锚点）。 */
    const val REGISTER_SENSOR_METHOD = "registerRecliningModeSensor"

    /** 把姿态写进触控节点的方法名（兜底拦截点，次选锚点）。 */
    const val WRITE_NODE_METHOD = "writeNodeForReclining"

    /** 传感器获取调用，用于 DexKit 语义定位注册方法。 */
    const val GET_DEFAULT_SENSOR_DESC =
        "Landroid/hardware/SensorManager;->getDefaultSensor(I)Landroid/hardware/Sensor;"
}
