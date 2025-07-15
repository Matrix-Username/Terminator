package com.skiy.terminator

import TestRunner
import android.app.Activity
import android.os.Bundle

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //ONLY FOR HOOK TEST!!!
        TestRunner.runTestsAndSummarize()
    }
}