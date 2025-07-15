package com.skiy.terminator.hooks.jvm;

public interface TerminatorEvents {

    void beforeCalled(MethodCall methodCall);
    void afterCalled(MethodCall methodCall);

}
