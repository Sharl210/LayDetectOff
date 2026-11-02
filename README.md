# LayDetectOff

**LayDetectOff v1.1.0** 通过 LSPosed 在 `system_server` 中移除“面朝下状态把息屏超时缩短到数秒”的分支。它不是翻转息屏手势，也不修改设置项或系统分区。

> v1.0.0 曾把 TP 触控节点 171 误判为息屏原因；真机记录显示拦截该链路后屏幕仍会熄灭。v1.1.0 已彻底移除这条错误实现，并改为下述 PowerManagerService 超时路径。请使用 v1.1.0，不要继续使用 v1.0.0。

## 已静态闭合的行为链

在 realme RMX5200 / Android 17（SDK 37）的 framework 源码与设备采样中，面朝下信号沿以下路径进入电源管理：

```text
FaceDownDetector.faceDownDetected()
  → mOnFlip.accept(true)
  → PowerManagerService.onFlip(true)
  → mIsFaceDown = true
  → updateUserActivitySummaryLocked()
  → getDefaultGroupScreenOffTimeoutOverrideLocked(screenOffTimeout, screenDimDuration)
```

目标方法的面朝下分支原本返回 `min(screenDimDuration, shortestScreenOffTimeout)`。本机运行参数中，通常息屏超时为 1,800,000 ms，面朝下覆写为 7,000 ms；由于更新时间已越过 +5,600 ms 的变暗边界，实际约在检测后 1,236 ms 熄屏。具体时间会随上一次真实用户活动时刻变化。

本模块的 DexKit 查询不依赖目标方法名或字段名，使用 `(long,long) -> long` 形状、24 小时守卫常量、实例布尔/整数状态字段及 `Math.min(long,long)` 调用共同筛选。对本机 `/system/framework/services.jar` 的只读查询有且只有 1 个候选，并进一步从该候选的调用/字段关系解析出面朝下字段、wake-lock 状态字段及超时策略方法。

Hook 在原方法之后执行：仅当 `mIsFaceDown == true` 且输入超时不是 24 小时特殊值时，重新调用该方法本来的 `ScreenTimeoutOverridePolicy`，把结果恢复为未应用 face-down `min` 的值。这样保留 wake-lock 超时策略与 24 小时守卫，不伪造用户活动，也不更改普通息屏时序。

## 作用域

模块 Manifest 声明 `xposedscope`，推荐作用域数组只有 `android`。目标代码运行在 Android 系统进程 `system_server`，不是 `com.oplus.gesture`。

## 构建

工程位于 [`module/`](module/)。在安装有 Android SDK、JDK 17+ 和 Gradle 8.9 的构建机上运行：

```bash
cd module
gradle :app:assembleRelease
```

发布版产物位于 `module/app/build/outputs/apk/release/`。Xposed API 82 只用于编译，不会打入 APK；DexKit 2.2.0 随模块打包。Manifest 与 `array/scope` 也保留为工程资源，便于通过标准 Android Gradle Plugin 构建。

## 安装与验证

本项目交付 APK 不会自动安装或启用。由设备所有者下载 Release APK，在 LSPosed 启用模块并确认推荐作用域是 `android`，再重启设备让 `system_server` 加载模块。

日志标签为 `LayDetectOff`。静态定位和 Hook 安装日志只能证明候选定位/注册过程；设备上的实际分支与屏幕行为仍应由用户自行确认。本次交付没有安装模块，也没有做运行态回归。

当前只对 realme RMX5200 / Android 17 的静态链路与 1 个 DexKit 候选完成闭环；未用第二个结构不同的 Android 版本验证，因此不宣称跨版本通杀。

## 回滚

在 LSPosed 禁用模块或卸载模块后重启设备即可撤销 Hook。运行时解出的 DexKit so 只写入系统 Context 的缓存目录；模块不改 `/system`、framework 文件、Settings 或 LSPosed 数据库。

## 许可证

MIT，见 [`LICENSE`](LICENSE)。
