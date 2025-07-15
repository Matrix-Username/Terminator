/*
 * Copyright 2025 Nazar Sladkovskyi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.skiy.terminatorkt

import com.skiy.terminator.hooks.jvm.HookTransformer
import com.skiy.terminator.hooks.jvm.TerminatorJVMHook
import com.skiy.terminator.hooks.jvm.TerminatorJVMHook.EntryPointType
import com.skiy.terminator.hooks.jvm.MethodCall
import com.v7878.unsafe.invoke.EmulatedStackFrame
import java.lang.invoke.MethodHandle
import java.lang.reflect.Executable
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaMethod

val entryPointType = EntryPointType.DIRECT

fun hookFunction(executable: Executable, action: (MethodCall) -> Unit) {
    val myHook = HookTransformer { original: MethodHandle, stack: EmulatedStackFrame ->
        val type = original.type()
        val paramCount = type.parameterCount()
        val initialArgs = arrayOfNulls<Any>(paramCount)
        val accessor = stack.accessor()

        for (i in 0 until paramCount) {
            initialArgs[i] = accessor.getValue(i)
        }

        val methodCall = MethodCall(original, stack, initialArgs)
        action(methodCall)

        val finalReturnValue: Any?
        val returnType = original.type().returnType()

        if (methodCall.shouldSkipOriginalMethodCall()) {
            finalReturnValue = methodCall.result

            if (returnType != Void.TYPE && returnType != Void::class.javaPrimitiveType) {
                accessor.setValue(EmulatedStackFrame.RETURN_VALUE_IDX, finalReturnValue)
            }

            if (!methodCall.isResultOverridden && (returnType != Void.TYPE && returnType != Void::class.javaPrimitiveType)) {
                System.err.println(
                    "Warning: Original method '${original.type()}' skipped for non-void method " +
                            "but no result provided via setReturnValue(). Effective return value will be ${finalReturnValue}."
                )
            }
        } else {
            val originalResult = original.invokeWithArguments(*methodCall.arguments)

            if (!methodCall.isResultOverridden) {
                methodCall.setResultFromOriginalIfNotOverridden(originalResult)
            }
            finalReturnValue = methodCall.result

            if (returnType != Void.TYPE && returnType != Void::class.javaPrimitiveType) {
                accessor.setValue(EmulatedStackFrame.RETURN_VALUE_IDX, finalReturnValue)
            }
        }
        finalReturnValue


    }

    TerminatorJVMHook.hook(
        executable,
        entryPointType,
        myHook,
        entryPointType
    )
}

fun hookFunctionAfterCall(executable: Executable, action: (MethodCall) -> Unit) {
    val myHook = HookTransformer { original: MethodHandle, stack: EmulatedStackFrame ->
        val type = original.type()
        val paramCount = type.parameterCount()
        val initialArgs = arrayOfNulls<Any>(paramCount)
        val accessor = stack.accessor()

        for (i in 0 until paramCount) {
            initialArgs[i] = accessor.getValue(i)
        }

        val methodCall = MethodCall(original, stack, initialArgs)

        val finalReturnValue: Any?
        val returnType = original.type().returnType()

        if (methodCall.shouldSkipOriginalMethodCall()) {
            finalReturnValue = methodCall.result

            if (returnType != Void.TYPE && returnType != Void::class.javaPrimitiveType) {
                accessor.setValue(EmulatedStackFrame.RETURN_VALUE_IDX, finalReturnValue)
            }

            if (!methodCall.isResultOverridden && (returnType != Void.TYPE && returnType != Void::class.javaPrimitiveType)) {
                System.err.println(
                    "Warning: Original method '${original.type()}' skipped for non-void method " +
                            "but no result provided via setReturnValue(). Effective return value will be ${finalReturnValue}."
                )
            }
        } else {
            val originalResult = original.invokeWithArguments(*methodCall.arguments)

            if (!methodCall.isResultOverridden) {
                methodCall.setResultFromOriginalIfNotOverridden(originalResult)
            }

            finalReturnValue = methodCall.result

            if (returnType != Void.TYPE && returnType != Void::class.javaPrimitiveType) {
                accessor.setValue(EmulatedStackFrame.RETURN_VALUE_IDX, finalReturnValue)
            }
        }
        finalReturnValue

        action(methodCall)
    }

    TerminatorJVMHook.hook(
        executable,
        entryPointType,
        myHook,
        entryPointType
    )
}
infix fun KFunction<*>.beforeCall(action: (MethodCall) -> Unit) {
    val executable: Executable = this.javaMethod ?: this.javaConstructor
    ?: error("Not a Java function")
    hookFunction(executable, action)
}

infix fun KFunction<*>.afterCall(action: (MethodCall) -> Unit) {
    val executable: Executable = this.javaMethod ?: this.javaConstructor
    ?: error("Not a Java function")
    hookFunction(executable) { methodCall ->
        action(methodCall)
    }
}

infix fun Executable.beforeCall(action: (MethodCall) -> Unit) {
    hookFunction(this, action)
}

infix fun Executable.afterCall(action: (MethodCall) -> Unit) {
    hookFunctionAfterCall(this, action)
}