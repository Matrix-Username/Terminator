package com.skiy.terminator.hooks.jvm.ti;

public class JVMTIException extends RuntimeException {
    public JVMTIException(int error) {
        super(JVMTI.GetErrorName(error));
    }
}
