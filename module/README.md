# LayDetectOff 模块工程

这是 LayDetectOff v1.1.0 的 Android 工程源码。模块作用域为 `android`，使用 DexKit 从 system_server 的 DEX 中唯一解析 face-down timeout override 候选。

## 工程内容

- `app/src/main/AndroidManifest.xml`：Xposed 元数据与 `@array/scope` 推荐作用域
- `app/src/main/res/values/arrays.xml`：作用域列表，仅包含 `android`
- `app/src/main/java/com/dsh/laydetectoff/Anchors.kt`：DexKit 查询使用的语义锚点
- `app/src/main/java/com/dsh/laydetectoff/HookEntry.kt`：system_server 引导、DexKit 查询、Hook 与策略保留
- `app/src/main/assets/xposed_init`：legacy Xposed 入口类

## 构建

需要 JDK 17+、Android SDK（compileSdk 35）与 Gradle 8.9：

```bash
gradle :app:assembleRelease
```

Release APK 位于 `app/build/outputs/apk/release/`。Xposed API 82 是 `compileOnly`；DexKit 2.2.0 随 APK 一同打包。

## 验证边界

当前静态定位和构建针对 realme RMX5200 / Android 17；没有实机安装测试或多版本动态验证。候选数不是行为成功的证明，未知 ROM 结构变化时模块会记录失败并停止安装 Hook，而不会按第一候选猜测。
