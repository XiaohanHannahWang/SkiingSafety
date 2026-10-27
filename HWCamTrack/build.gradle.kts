// 顶层构建脚本：只声明插件版本，具体配置在 :app 模块里。
//
// 版本组合（这几者互相绑定，改动时请一起核对）：
//   AGP 8.7.3  <-> Gradle 8.9  <-> JDK 17+  <-> Kotlin 2.0.21  <-> compileSdk 35
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
