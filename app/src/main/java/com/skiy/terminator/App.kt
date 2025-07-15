package com.skiy.terminator

import android.app.Application

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        //ONLY FOR HOOK TEST!!!
        System.loadLibrary("nativetest")
    }

}