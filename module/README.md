# module — Android 工程

LSPosed 模块的 Gradle 工程。根目录的 [README](../README.md) 有机制原理和安装说明。

## 构建

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

需要 Android SDK（compileSdk 35）与 JDK 17+。

## 结构

```
app/src/main/
├── AndroidManifest.xml          模块元数据 + 推荐作用域声明
├── assets/xposed_init           Xposed 入口类名
├── java/com/dsh/laydetectoff/
│   ├── Anchors.kt               全部语义锚点与链路取证笔记
│   └── HookEntry.kt             三层拦截实现
└── res/values/
    ├── arrays.xml               推荐作用域包名列表
    └── strings.xml              应用名与描述
```

## 依赖

| 依赖 | 来源 |
|---|---|
| `org.luckypray:dexkit:2.2.0` | Maven Central |
| `de.robv.android.xposed:api:82` | 阿里云镜像（官方源已下线） |

Xposed API 是 `compileOnly`，不会打包进 APK。

## 改作用域

如果你的机型目标进程不是 `com.oplus.gesture`，
改 `app/src/main/res/values/arrays.xml` 里的 `scope` 数组即可，
LSPosed 会据此自动勾选。

## 锚点调整

跨版本失效时看 `Anchors.kt` 头部注释里的取证方法，
用 `adb shell dumpsys sensorservice` 找当前机器的传感器类型号。
