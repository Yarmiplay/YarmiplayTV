package com.yarmiplaytv

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Runs before the app's onCreate, so the app skips its update check while tests run. */
class YarmiplayTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        System.setProperty("yarmiplaytv.noUpdateCheck", "1")
        super.onCreate(arguments)
    }
}
