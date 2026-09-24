package com.burgess.schedule.school.sync.golden

import android.os.Process
import android.util.Log

class ShizukuUserService : IUserService.Stub() {
    companion object {
        private const val TAG = "GoldenUserService"

        init {
            Log.i(TAG, "CLASS_INITIALIZED pid=${Process.myPid()} uid=${Process.myUid()}")
        }
    }

    init {
        Log.i(TAG, "CONSTRUCTOR_READY pid=${Process.myPid()} uid=${Process.myUid()}")
    }

    override fun exit() {
        Log.i(TAG, "exit() pid=${Process.myPid()} uid=${Process.myUid()}")
        System.exit(0)
    }

    override fun getUid(): Int = Process.myUid().also {
        Log.i(TAG, "getUid() uid=$it pid=${Process.myPid()}")
    }

    override fun ping(): String = "pong".also {
        Log.i(TAG, "ping() uid=${Process.myUid()} pid=${Process.myPid()}")
    }

    override fun destroy() {
        Log.i(TAG, "destroy() no-op uid=${Process.myUid()} pid=${Process.myPid()}")
    }
}
