// Android に依存しないロジック。JVM 上で単体テストできる。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}
