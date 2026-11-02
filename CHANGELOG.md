# LayDetectOff v1.1.0

- 修正 v1.0.0 的错误目标：移除 TP 节点 171 及 `com.oplus.gesture` 拦截代码。
- 作用域改为 `android`，Hook 运行于 `system_server`。
- DexKit 语义匹配唯一定位 PowerManagerService 面朝下超时覆写方法；保留原始 wake-lock 超时策略与 24 小时特殊守卫。
- 静态查询针对本机 Android 17 `services.jar` 返回 1 个候选；未执行模块安装或设备动态测试。
- APK 及 SHA-256 随 GitHub Release 发布。
