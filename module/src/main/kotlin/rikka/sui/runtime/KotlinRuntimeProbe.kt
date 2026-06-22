package rikka.sui.runtime

object KotlinRuntimeProbe {
    @JvmStatic
    fun loaded(): Boolean = true

    @JvmStatic
    fun runtimeName(): String = "sui-kotlin-runtime"
}
