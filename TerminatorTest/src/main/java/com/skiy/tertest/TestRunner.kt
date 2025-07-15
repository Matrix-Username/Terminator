import android.util.Log
import com.skiy.terminatorkt.CString
import com.skiy.terminatorkt.Pointer
import com.skiy.terminatorkt.TerminatorNative
import com.skiy.terminatorkt.dump
import com.skiy.terminatorkt.readCString
import com.skiy.terminatorkt.returns
import com.skiy.terminatorkt.memory.Struct
import com.skiy.terminatorkt.memory.StructCompanion
import com.skiy.terminatorkt.toNative
import com.skiy.terminatorkt.toPointer
import com.skiy.terminatorkt.utils.addressByTarget
import com.skiy.terminatorkt.utils.asCallable
import com.v7878.foreign.Arena
import java.util.concurrent.ConcurrentHashMap


class TestData(pointer: Pointer) : Struct(pointer) {
    companion object : StructCompanion<TestData>() {
        val id = int("id")
        val value = double("value")

        override fun create(pointer: Pointer) = TestData(pointer)
    }

    var id: Int by Mapped()
    var value: Double by Mapped()

    override fun toString(): String = "TestData(id=$id, value=$value) at 0x${pointer.address().toString(16)}"
}

object TestRunner {

    private const val TAG = "NativeHookTester"
    private const val LIB_NAME = "libnativetest.so"
    private val testResults = ConcurrentHashMap<String, Boolean>()

    fun installHooks() {
        Log.i(TAG, "--- Installing All Hooks ---")

        TerminatorNative.hook {
            target(LIB_NAME, "test_simple_function")
            returns<Int>()
            params(Int::class, Int::class)

            onCalled {
                val a = arg<Int>(0)
                val b = arg<Int>(1)
                val testName = "Simple Function Hook"

                Log.d(TAG, "[Hook] Intercepted test_simple_function($a, $b)")
                testResults[testName] = (a == 5 && b == 7)

                return@onCalled a * b
            }
        }

        TerminatorNative.hook {
            target(LIB_NAME, "test_pointer_args")
            returns<CString>() // const char*
            params(CString::class, Pointer::class)
            onCalled {
                val strPtr = arg<Pointer>(0)
                val testName = "Pointer Args Hook"

                val inputString = strPtr.readCString()
                Log.d(TAG, "[Hook] Intercepted test_pointer_args(${dumpArgsSmart().joinToString(", ")})")

                val receivedCorrectly = (inputString == "Hello from C++")

                testResults[testName] = receivedCorrectly

                return@onCalled arena.allocateFrom("Hooked response string")
            }
        }

        TerminatorNative.hook {
            target(LIB_NAME, "_ZN9TestClass13static_methodEv")
            returns<Pointer>()
            params()

            onCalled {
                arena.allocateFrom("Hooked static string")
            }

        }

        TerminatorNative.hook {
            target(LIB_NAME, "test_struct_by_pointer")
            params(Pointer::class)
            returns<Void>()

            onCalled {
                val structPointer = arg<Pointer>(0)
                val testData = TestData.of(structPointer)
                testData.id = 88

                Log.i(TAG, testData.dump())

                callOriginal()
            }
        }

        //Test method calling
        val method = addressByTarget(
            libraryName = LIB_NAME,
            symbol = "test_pointer_args"
        ).toPointer()
            .asCallable<(CString, Pointer) -> CString>()

        val result = method("Hello", 32.toNative(Arena.ofConfined())) as CString

        Log.i(TAG, "Method calling test: $result")

        Log.i(TAG, "--- All Hooks Installed ---")
    }

    fun runTestsAndSummarize() {
        installHooks()

        Log.i(TAG, "Loading native library to trigger tests...")
    }
}