# Terminator


![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)
![JitPack](https://jitpack.io/v/Matrix-Username/Terminator.svg)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)

A powerful, modern hooking framework for Android that lets you intercept native (C/C++) and JVM (Java/Kotlin) calls with a type-safe Kotlin DSL, requiring no root and no NDK.

## 📖 Documentation

For full guides, API reference, and advanced examples, check out the [**Official Documentation**](https://terminator-docs.vercel.app/docs/introduction).

## ✨ Features

- **Pure Java/Kotlin**: No C++ or NDK toolchain required to write hooks.
- **Unified API**: A single, consistent DSL for hooking both native functions and JVM methods.
- **Type Safety**: Leverage Kotlin's type system to catch errors at compile time.
- **Expressive DSL**: A clean, readable, and modern API using Kotlin's best features.
- **Powerful Memory API**: Read, write, and map native pointers and C-style structs directly from Kotlin.
- **No Root Required**: Operates entirely within your application's process.

## 🚀 Installation

Add the JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
}
```

Add the dependencies to your module's `build.gradle.kts`:

```kotlin
dependencies {
    // Main library
    implementation("com.github.Matrix-Username.Terminator:Terminator:1.0.2") 
    // Kotlin wrapper & DSL
    implementation("com.github.Matrix-Username.Terminator:TerminatorHookKotlin:1.0.2") 
}
```

## 🔧 Quick Start

### Hooking a Native Function (`libc.so`)

Intercept calls to `open` to log every file the application accesses.

```kotlin
import android.util.Log
import com.skiy.terminatorkt.*

fun hookFileOpens() {
    TerminatorNative.hook {
        target("libc.so", "open")
        returns<Int>()
        params(CString::class, Int::class)

        onCalled {
            val path = arg<CString>(0)
            Log.d("FileMonitor", "Opening file: $path")
            callOriginal()
        }
    }
}
```

### Hooking a JVM Method (`android.util.Log`)

Intercept calls to `Log.d` and prepend a prefix to the message.

```kotlin
import android.util.Log
import com.skiy.terminatorkt.beforeCall
import kotlin.reflect.full.functions

fun hookLogging() {
    val target = Log::class.functions.first { it.name == "d" }

    target beforeCall { methodCall ->
        val args = methodCall.arguments
        args[1] = "[HOOKED] ${args[1]}"
    }
}
```

## 🙏 Acknowledgements

**Terminator** is based on the [PanamaPort](https://github.com/vova7878/PanamaPort) library by [vova7878](https://github.com/vova7878).

**PanamaPort** provides a low-level implementation of the FFM (Foreign Function & Memory) API for Android, which serves as the engine behind all of Terminator’s hooking and memory manipulation capabilities.
## 🤝 Contributing

Contributions are welcome! Please feel free to open an issue or submit a pull request.

## 📄 License

This project is licensed under the Apache 2.0 License. See the [LICENSE](LICENSE) file for details.
