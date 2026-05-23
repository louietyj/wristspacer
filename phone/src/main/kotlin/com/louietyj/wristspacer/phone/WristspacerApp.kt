package com.louietyj.wristspacer.phone

import android.app.Application
import android.util.Log
import java.lang.reflect.Method

class WristspacerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        exemptHiddenApis()
    }

    /**
     * VMRuntime double-reflection bypass. This exempts all hidden APIs from the block list,
     * allowing us to call android.app.smartspace.* and android.os.ServiceManager via reflection.
     *
     * Works on most Android versions through 15. If it fails on 16+, the app will surface an error
     * in the UI and the user can run:
     *   adb shell settings put global hidden_api_policy 1
     * or use Shizuku to do the same (handled in ShizukuHelper).
     */
    private fun exemptHiddenApis() {
        try {
            // getDeclaredMethod is public API — we use it to reach VMRuntime without triggering
            // the restriction check that fires on direct calls to hidden classes.
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                Array<Class<*>>::class.java
            )

            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")

            val getRuntime = (getDeclaredMethod.invoke(
                vmRuntimeClass, "getRuntime", null
            ) as Method).apply { isAccessible = true }

            val runtime = getRuntime.invoke(null)

            val setHiddenApiExemptions = (getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf(Array<String>::class.java)
            ) as Method).apply { isAccessible = true }

            // "L" exempts all classes (all descriptors start with L in JVM notation)
            setHiddenApiExemptions.invoke(runtime, arrayOf("L"))
            Log.d(TAG, "Hidden API bypass applied")
        } catch (e: Exception) {
            Log.w(TAG, "VMRuntime bypass failed — hidden APIs may be blocked: ${e.message}")
            // Fallback handled in ShizukuHelper.applyHiddenApiPolicyViaShizuku()
        }
    }

    companion object {
        private const val TAG = "WristspacerApp"
    }
}
