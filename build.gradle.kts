plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 内置 Kotlin 支持；这里只用来固定 Kotlin 编译器版本，与 Compose 编译器插件保持一致
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
