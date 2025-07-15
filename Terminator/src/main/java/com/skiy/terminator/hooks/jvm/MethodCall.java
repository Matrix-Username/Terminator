package com.skiy.terminator.hooks.jvm;

import com.v7878.unsafe.invoke.EmulatedStackFrame;

import java.lang.invoke.MethodHandle;

public class MethodCall {

    private final MethodHandle methodHandle;
    private final EmulatedStackFrame stackFrame;
    private Object[] arguments;

    private Object result;
    private boolean resultOverridden;
    private boolean skipOriginalMethodCall = false;

    public MethodCall(MethodHandle methodHandle, EmulatedStackFrame stackFrame, Object[] initialArguments) {
        this.methodHandle = methodHandle;
        this.stackFrame = stackFrame;
        this.arguments = new Object[initialArguments.length];
        System.arraycopy(initialArguments, 0, this.arguments, 0, initialArguments.length);

        this.result = null;
        this.resultOverridden = false;
        this.skipOriginalMethodCall = false;
    }

    public EmulatedStackFrame getStackFrame() {
        return stackFrame;
    }

    public MethodHandle getMethodHandle() {
        return methodHandle;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public void setArguments(Object[] arguments) {
        this.arguments = arguments;
    }

    public Object getResult() {
        return result;
    }

    public void setReturnValue(Object newResult) {
        this.result = newResult;
        this.resultOverridden = true;
        this.skipOriginalMethodCall = true;
    }

    public boolean isResultOverridden() {
        return resultOverridden;
    }

    public void setSkipOriginalMethodCall(boolean skip) {
        this.skipOriginalMethodCall = skip;
    }

    public boolean shouldSkipOriginalMethodCall() {
        return skipOriginalMethodCall;
    }

    public void setResultFromOriginalIfNotOverridden(Object originalResult) {
        if (!this.resultOverridden) {
            this.result = originalResult;
        }
    }
}