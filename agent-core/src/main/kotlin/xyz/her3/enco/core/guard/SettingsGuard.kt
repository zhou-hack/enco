package xyz.her3.enco.core.guard

object SettingsGuard {
    private val navigationLabels =
        setOf(
            "蓝牙",
            "藍牙",
            "Bluetooth",
            "WLAN",
            "Wi-Fi",
            "无线网络",
            "無線網路",
            "显示",
            "顯示",
            "显示与亮度",
            "顯示與亮度",
            "Display",
            "Display & brightness",
            "声音与振动",
            "聲音與觸感",
            "聲音與震動",
            "Sound & vibration",
            "关于手机",
            "關於手機",
            "我的设备",
            "我的裝置",
            "About phone",
            "My device",
            "电池",
            "電池",
            "省电与电池",
            "省電與電池",
            "Battery",
            "网络和互联网",
            "網路和網際網路",
            "Network & internet",
            "已连接的设备",
            "已連接的裝置",
            "Connected devices",
        )

    private val sensitiveTerms =
        listOf(
            "密码",
            "密碼",
            "验证码",
            "驗證碼",
            "付款",
            "支付",
            "转账",
            "轉帳",
            "password",
            "passcode",
            "verification code",
            "payment",
        )

    fun canTap(label: String): Boolean = label.trim() in navigationLabels

    fun canShare(label: String): Boolean =
        canTap(label) || label in
            setOf(
                "设置",
                "設定",
                "Settings",
                "可用设备",
                "可用裝置",
                "Available devices",
                "已配对设备",
                "已配對裝置",
                "Paired devices",
                "设备名称",
                "裝置名稱",
                "Device name",
                "亮度",
                "亮度等级",
                "亮度等級",
                "自动亮度",
                "自動亮度",
                "Brightness",
                "Brightness level",
                "Adaptive brightness",
            )

    fun requiresTakeover(label: String): Boolean = sensitiveTerms.any { label.contains(it, ignoreCase = true) }
}
