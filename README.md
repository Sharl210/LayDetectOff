# LayDetectOff

关闭 realme / OPPO / 一加 系统里**没有 UI 开关、也没有任何说明文字**的「平放检测」机制。

一个 LSPosed 模块，用 [DexKit](https://github.com/LuckyPray/DexKit) 做跨版本语义定位。

---

## 这个机制到底是什么

不是「息屏手势」，不是「黑屏手势」，不是「翻转静音」，也不是 `wake_gesture_enabled`。

真实链路（已在 realme RMX5200 / Android 17 / SDK 37 上逐层取证）：

```
lay_detect 传感器（qti.sensor.lay_detect, type = 33171082）
   │  全机唯一持有者：com.oplus.gesture（sensorservice 实测 connections=1）
   ▼
OplusGestureEngineService$2.onSensorChanged()   ← 判定 getType() == 33171082
   ▼
OplusGestureEngineService.writeNodeForReclining(values[0])
   ▼
OplusTouchNodeManager.writeNodeFileByDevice(dId, 171, String.valueOf((int) v))
   ▼
触控 IC（TP）的 171 号「倾斜 / 平放模式」节点
```

### 为什么现象"有时有、有时没有"

三个原因叠加，这不是随机故障：

1. 该传感器**只在屏幕点亮时注册**，屏幕熄灭时注销。
2. `values[0]` 在 **0 / 1 / 2** 三档之间跳变，写入触控固件的档位随之改变。
3. 触控固件按档位改变触摸判定，用户侧观感就是"盖住屏幕后屏幕行为异常"。

实测传感器事件（跳变很明显）：

```
lay_detect last 8 events
 20:01:11 → 0    20:03:13 → 2    20:05:32 → 0    20:19:26 → 0
 20:37:58 → 0    21:59:28 → 2    22:14:47 → 1    22:15:13 → 0
```

### 已排除的相似路径

`ScreenOffGestureService` 里另有一条「抬起唤醒」倾角传感器：
值 `1` → 延迟 500ms → `PowerManager.goToSleep()`。

**不是本次对象**：它的开关是 `Settings.Secure` 的 `oplus_customize_gesture_wake_up_arouse`，
本机实测为 `0`，且该监听器在 sensorservice 里没有注册记录。本模块不碰它。

---

## 模块怎么工作

三层拦截，任何一层生效即达成目标；**全部失败就不装 Hook**，不会乱挂。

| 层 | 位置 | 动作 | 作用 |
|---|---|---|---|
| 探针 | `isTouchNodeSupport(dId, 171)` | 只记录返回值 | 只读，报告固件是否支持 171 节点 |
| **L1** | `registerRecliningModeSensor()` | 直接 return | **断源**：传感器根本不注册 |
| **L2a** | `writeNodeForReclining(float)` | 直接 return | 兜底：姿态写不出去 |
| **L2b** | `writeNodeFileByDevice(dId, node, v)` | 仅 `node == 171` 时吞掉 | 卡死最后一道写触控 IC 的动作 |
| **L3** | DexKit 结构查询 | 动态定位上述目标 | 跨版本：改名也能找回 |

### 为什么不拦 `onSensorChanged`

同一个方法里还跑着**陀螺仪校准**（`type == 4`）与 AMD 传感器分支。
整段吞掉会破坏校准状态机，症状是手势功能整体异常。
所以只拦更窄的位置。

### 为什么语义锚点选 33171082

这个传感器类型号由**内核 / 传感器 HAL 上报**，属于设备级稳定常量，
不随 APK 混淆变化，也不随 ROM 版本变。比任何类名/方法名都稳。

---

## 安装

### 1. 下载

从 [Releases](../../releases) 页面下载最新的 `LayDetectOff-vX.Y.Z.apk`。

### 2. 安装并启用

```bash
adb install -r LayDetectOff-v1.0.0.apk
```

然后打开 LSPosed → 模块 → 「平放检测关闭」→ 启用。

**作用域已内置**：模块在 Manifest 里声明了 `xposedscope`，
LSPosed 会自动把 `com.oplus.gesture` 勾选为作用域，不需要手动翻系统应用列表。

最后**重启手机**。`com.oplus.gesture` 是常驻系统服务，不重启不会重新加载模块。

### 3. 验证

```bash
adb logcat -s LayDetectOff
```

正常应看到：

```
LayDetectOff: 进入目标进程 com.oplus.gesture（pid=xxxx）
LayDetectOff: L1 已 Hook ...OplusGestureEngineService#registerRecliningModeSensor
LayDetectOff: L2a 已 Hook ...#writeNodeForReclining
LayDetectOff: L2b 已 Hook com.oplus.touchnode.OplusTouchNodeManager#writeNodeFileByDevice
LayDetectOff: 安装完成，共 3 个 Hook 点
```

之后平放设备，出现下面这行即表示拦截生效：

```
LayDetectOff: [L1-阻止注册lay_detect] 已拦截（累计 1 次）
```

### 4. 排错

| 现象 | 含义 |
|---|---|
| 完全没有 `LayDetectOff` 输出 | 作用域没勾上 / 模块未启用 / 没重启进程 |
| 有「进入目标进程」但无 Hook 成功 | 类名或方法名变了，请开 issue 附日志 |
| Hook 成功但平放仍异常 | 可能是另一条路径，请附完整日志 |

模块里的探针会打印：

```
探针：isTouchNodeSupport(dId=0, node=171) = true / false
```

本机 `/proc/touchpanel/` 下没有 171 对应的节点文件，
所以这行是判断「固件是否真的支持该节点」的唯一直接证据。
若为 `false`，说明这条链路在空转，需要换方向排查。

---

## 自行编译

### 常规方式（有 Android SDK）

```bash
cd module
./gradlew :app:assembleDebug
```

产物在 `module/app/build/outputs/apk/debug/app-debug.apk`。

依赖说明：
- DexKit `org.luckypray:dexkit:2.2.0` 从 Maven Central 拉取
- Xposed API `de.robv.android.xposed:api:82` 从阿里云镜像拉取
  （官方源 `api.xposed.info` 已下线）

### 无 Android SDK 的手工构建

`tools/` 目录是本项目在**没有 aapt2 的 arm64 Android 设备**上完成构建时用的脚本。
Google 只为 aapt2 提供 x86_64 构建，所以设备上跑不了 Gradle。

| 脚本 | 作用 |
|---|---|
| `mkaxml.py` | 手写二进制 AndroidManifest.xml（含 Xposed meta-data 与作用域引用） |
| `mkarsc.py` | 手写最小 resources.arsc（只含 `array/scope` 字符串数组） |
| `SignApk.java` | 用 apksig 做 v1+v2+v3 签名 |
| `VerifyApk.java` | 离线校验签名是否有效 |

流程：`kotlinc` 编译 → `d8` 转 dex → 上面四个脚本完成清单、资源、打包、签名。

这些脚本里有几处容易踩的坑，都写在注释里了，例如：
`ResTable_config` 首字段必须是自身长度（否则报 `Config size < 8`）、
`resources.arsc` 在 APK 内必须 stored 不压缩。

---

## 兼容性

| 项目 | 值 |
|---|---|
| 已验证设备 | realme RMX5200 / Android 17 (SDK 37) / realmeUI |
| 理论适用 | 搭载 `com.oplus.gesture` 的 realme / OPPO / 一加 机型 |
| minSdk | 29 |
| 架构 | 含 `arm64-v8a` 的 DexKit native 库 |

跨版本能力来自 L3 的 DexKit 语义查询。
换 ROM 后若日志里 `候选数` 异常（0 或明显偏大），说明锚点需要调整，
请开 issue 附上 `adb logcat -s LayDetectOff` 的完整输出。

---

## 安全边界

- 只做**方法级 Hook**，不改任何系统文件、不写系统分区、不修改 APK。
- 所有手段**可逆**：卸载模块 + 重启即恢复原状。
- 不涉及 root 写操作，不修改 `Settings` 数据库，不碰 LSPosed 作用域数据库。
- 对 `OplusTouchNodeManager` **只在 `node == 171` 时拦截**，
  其他触控节点（双击、防误触、水下相机等）全部放行。

---

## 许可

MIT，见 [LICENSE](LICENSE)。

本项目仅供学习研究与自有设备定制使用。
