package xyz.her3.enco.device.shell

import android.content.ClipData
import android.os.Build
import android.os.IBinder
import android.os.Process

internal class WeChatClipboard {
    private val api = Class.forName("android.content.IClipboard")
    private val binder =
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
    private val service =
        Class
            .forName(
                "android.content.IClipboard\$Stub",
            ).getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    private val packageName = "com.android.shell"
    private val user = Process.myUid() / 100_000

    fun read(): ClipData? = invoke("getPrimaryClip") as? ClipData

    fun hasClip(): Boolean = invoke("hasPrimaryClip") == true

    fun write(clip: ClipData?) {
        if (clip == null) invoke("clearPrimaryClip") else invoke("setPrimaryClip", clip)
    }

    private fun invoke(
        name: String,
        clip: ClipData? = null,
    ): Any? {
        val signature = mutableListOf<Class<*>>()
        val args = mutableListOf<Any?>()
        if (clip != null) {
            signature.add(ClipData::class.java)
            args.add(clip)
        }
        signature.add(String::class.java)
        args.add(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            signature.add(String::class.java)
            args.add(null)
        }
        signature.add(Int::class.javaPrimitiveType!!)
        args.add(user)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            signature.add(Int::class.javaPrimitiveType!!)
            args.add(0)
        }
        return api.getMethod(name, *signature.toTypedArray()).invoke(service, *args.toTypedArray())
    }
}
