package io.github.system32crowdstrikewin11.openaircursor

/** Thin bridge to the NDK library built from src/main/cpp. */
object NativeBridge {
    init {
        System.loadLibrary("openaircursor")
    }

    external fun version(): String
}
