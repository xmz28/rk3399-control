# 第三方说明

本仓库 MIT 许可适用于自有源码、脚本与文档，不替代第三方组件的许可。

| 组件 | 用途 | 上游 |
| --- | --- | --- |
| AndroidX / Jetpack Compose | Android 界面与生命周期 | [AndroidX](https://developer.android.com/jetpack/androidx) |
| Kotlin / Compose Compiler Plugin | 编译 Kotlin 与 Compose | [Kotlin](https://github.com/JetBrains/kotlin) |
| Android Gradle Plugin | Android 构建 | [Android 构建文档](https://developer.android.com/build) |
| Gradle / Gradle Wrapper | 构建工具及引导程序 | [Gradle](https://github.com/gradle/gradle) |
| Foojay Toolchain Resolver | Gradle JVM 工具链解析 | [插件项目](https://github.com/gradle/foojay-toolchains) |
| JUnit / AndroidX Test | 测试依赖 | [JUnit](https://github.com/junit-team/junit4)、[AndroidX Test](https://github.com/android/android-test) |

依赖版本列于 gradle/libs.versions.toml、settings.gradle.kts 与 Gradle Wrapper 配置。依赖从官方构建仓库解析，保留其各自许可证；Gradle Wrapper 引导文件保留已有版权声明。

板端运行还依赖 Samba、Copyparty、Primitive FTPd、Alpine、dnsmasq 等外部软件，本仓库不打包这些软件或设备固件；相关资源随 [NAS 配套项目](https://github.com/xmz28/rk3399-android-nas) 的说明准备。
