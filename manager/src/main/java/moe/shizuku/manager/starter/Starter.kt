package moe.shizuku.manager.starter

import android.content.Context
import java.io.File

object Starter {

    private fun getStarterFile(context: Context): File {
        val applicationContext = context.applicationContext
        return File(applicationContext.applicationInfo.nativeLibraryDir, "libshizuku.so")
    }

    fun userCommand(context: Context): String = getStarterFile(context).absolutePath

    fun adbCommand(context: Context): String = "adb shell ${userCommand(context)}"

    fun internalCommand(context: Context): String {
        val applicationContext = context.applicationContext
        return "${userCommand(applicationContext)} --apk=${applicationContext.applicationInfo.sourceDir}"
    }
}
