# 保住 Xposed 入口类与 xposed_init 里声明的一切
-keep class com.dsh.laydetectoff.HookEntry { *; }
-keep class com.dsh.laydetectoff.** { *; }

# DexKit
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.** { *; }

# Xposed API 由宿主提供，不打包
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# Kotlin 协程/元数据最小保留
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
