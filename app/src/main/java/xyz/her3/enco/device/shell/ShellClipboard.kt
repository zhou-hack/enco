package xyz.her3.enco.device.shell

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.UUID

@Suppress("DEPRECATION")
internal fun pasteFromClipboard(
    automation: UiAutomation,
    text: String,
    field: AccessibilityNodeInfo?,
    replaceExisting: Boolean = false,
    targetUnchanged: () -> Boolean,
): Boolean {
    val clipboard = ShellClipboard()
    val previousDescription = clipboard.description()
    val hasPreviousClip = clipboard.hasClip()
    if (previousDescription == null && hasPreviousClip) return false
    if (previousDescription != null && !previousDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return false
    val previous = clipboard.read()
    if (previousDescription != null && previous == null) return false
    if (previous != null && (0 until previous.itemCount).any { previous.getItemAt(it).uri != null }) return false
    val token = UUID.randomUUID().toString()
    val clip =
        ClipData.newPlainText("enco", text).apply {
            description.extras =
                PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                    putString("xyz.her3.enco.clipboard_token", token)
                }
        }

    fun ownsClipboard() = clipboard.description()?.extras?.getString("xyz.her3.enco.clipboard_token") == token
    return try {
        clipboard.write(clip)
        // 给前台编辑器留出处理焦点与剪贴板更新的时间。
        SystemClock.sleep(250)
        if (!ownsClipboard()) {
            Log.w("enco.device", "临时剪贴板已被替换，取消粘贴")
            return false
        }
        if (!targetUnchanged()) {
            Log.w("enco.device", "粘贴前目标窗口已变化")
            return false
        }
        if (field != null && (
                !field.refresh() || !field.isFocused || field.isPassword ||
                    (!replaceExisting && !field.text.isNullOrEmpty() && !field.isShowingHintText)
            )
        ) {
            return false
        }
        if (replaceExisting && field != null && !field.text.isNullOrEmpty() && !field.isShowingHintText) {
            val selection =
                android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, field.text.length)
                }
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)) return false
        }
        val applied =
            if (field?.actionList?.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE) == true) {
                field.performAction(AccessibilityNodeInfo.ACTION_PASTE) || injectPasteKey(automation)
            } else {
                injectPasteKey(automation)
            }
        SystemClock.sleep(700)
        if (field != null && field.refresh()) {
            val exact = field.text?.toString() == text
            if (!exact) Log.w("enco.device", "粘贴后输入框内容未能核对")
            exact
        } else {
            applied
        }
    } finally {
        if (ownsClipboard()) clipboard.write(previous)
    }
}

private fun injectPasteKey(automation: UiAutomation): Boolean {
    val time = SystemClock.uptimeMillis()
    val down =
        KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE, 0).apply {
            source = InputDevice.SOURCE_KEYBOARD
        }
    val pressed = automation.injectInputEvent(down, true)
    SystemClock.sleep(50)
    val up =
        KeyEvent.changeAction(down, KeyEvent.ACTION_UP).apply {
            source = InputDevice.SOURCE_KEYBOARD
        }
    return automation.injectInputEvent(up, true) && pressed
}

private class ShellClipboard {
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

    fun description(): ClipDescription? = invoke("getPrimaryClipDescription") as? ClipDescription

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
