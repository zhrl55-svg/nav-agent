package com.navagent.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.Locale

/**
 * 蓝牙自动启动：连上「指定的车机」后自动把语音服务拉起来。
 *
 * ## 做什么
 *
 * 目标设备（按**名称前缀**匹配，比如车机蓝牙叫「Ford」「迈巴赫」「车机」）
 * 连上手机后：
 *  1. 启动 [VoiceService] 前台服务 → 唤醒词开始常驻监听，用户直接说话就行；
 *  2. 发一条常驻通知 → 点通知能进聊天页。
 *
 * ## 为什么不直接弹 Activity
 *
 * Android 10+ 有后台启动 Activity 的限制（BAL）。官方列的豁免条件里
 * **没有「已连接蓝牙设备」这一条** —— 前台服务本身也不算豁免。
 * 所以想在后台弹界面，只有两条正路：
 *  - 用户授予 `SYSTEM_ALERT_WINDOW`（悬浮窗权限）；
 *  - 用系统发的 `PendingIntent`（比如通知点击）。
 *
 * 后台常驻服务 + 通知点击，正好落在豁免条件第 3 条里，稳。
 *
 * ## Android 12+ 的权限
 *
 * 蓝牙相关的 `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` 是**运行时权限**，
 * 不申请就拿不到设备名，也收不到 `ACTION_ACL_CONNECTED`。
 * 见 [BluetoothPermissions]。
 */
object BluetoothAutoStart {

    private const val TAG = "BluetoothAutoStart"
    private const val CHANNEL_ID = "bt_autostart"
    private const val NOTIF_ID = 1002

    /** 目标设备已连上（避免同一次连接重复触发） */
    private var lastTriggeredDevice: String? = null

    private var registered = false
    private var receiver: BroadcastReceiver? = null

    /**
     * 注册蓝牙连接广播。
     *
     * 必须在用户明确开启「蓝牙自动启动」时调用（因为要申请运行时权限）。
     */
    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext

        // Android 12+ 没 BLUETOOTH_CONNECT 时注册广播会抛 SecurityException。
        // 早期版本不拦这个（读设备名时才抛），但新版会直接拒绝注册。
        if (!hasPermission(app)) {
            Dbg.log("蓝牙自动启动：缺 BLUETOOTH_CONNECT 权限，无法注册广播")
            return
        }

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                        handleConnected(ctx, device)
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        // 断开要清掉触发标记，否则下次再连同一个设备不会触发
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                        val id = device?.address ?: return
                        if (lastTriggeredDevice == id) {
                            Dbg.log("蓝牙自动启动：$id 已断开，清除触发标记")
                            lastTriggeredDevice = null
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Android 14+ 广播接收器必须显式指定 exported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(r, filter)
        }
        receiver = r
        registered = true
        Dbg.log("蓝牙自动启动：广播接收器已注册")
    }

    fun unregister(context: Context) {
        if (!registered) return
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
        receiver = null
        registered = false
        Dbg.log("蓝牙自动启动：广播接收器已注销")
    }

    /**
     * 处理「设备连上」事件。
     *
     * 匹配规则：设备名包含关键词（不区分大小写）。
     */
    @SuppressLint("MissingPermission")
    private fun handleConnected(context: Context, device: BluetoothDevice?) {
        if (device == null) return
        val name = try {
            device.name
        } catch (e: SecurityException) {
            Dbg.log("蓝牙自动启动：读设备名被拒（缺 BLUETOOTH_CONNECT）")
            null
        } ?: return

        val keyword = Prefs.bluetoothTargetName.trim()
        if (keyword.isBlank()) {
            Dbg.log("蓝牙自动启动：未配置目标设备名，忽略连接 $name")
            return
        }
        if (!name.contains(keyword, ignoreCase = true)) {
            Dbg.log("蓝牙自动启动：$name 不匹配「$keyword」，忽略")
            return
        }

        // 同一次连接只触发一次。断开后要清掉，下次再连还能触发。
        val id = device.address ?: name
        if (lastTriggeredDevice == id) {
            Dbg.log("蓝牙自动启动：$name 已触发过，跳过")
            return
        }
        lastTriggeredDevice = id

        Dbg.log("蓝牙自动启动：命中 $name，启动语音服务")
        trigger(context, name)
    }

    /**
     * 真正执行启动。
     *
     * 顺序有讲究：**先起服务再发通知**。
     * 服务起来之后通知里的「说 XX 唤醒」才是真的，
     * 否则会有一条通知说能唤醒、实际没在监听。
     */
    private fun trigger(context: Context, deviceName: String) {
        val app = context.applicationContext

        // 1) 拉起前台服务，唤醒词开始常驻监听
        runCatching {
            VoiceService.start(app)
        }.onFailure {
            Dbg.log("蓝牙自动启动：启动语音服务失败 ${it.message}")
        }

        // 2) 发常驻通知，点它能进聊天页
        runCatching {
            notifyReady(app, deviceName)
        }.onFailure {
            Dbg.log("蓝牙自动启动：发通知失败 ${it.message}")
        }
    }

    private fun notifyReady(context: Context, deviceName: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "车载就绪", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "车机已连接，语音助手待命"
                    setShowBadge(false)
                }
            )
        }

        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val wake = Prefs.primaryWakeWord()
        val notif: Notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("已连接 $deviceName")
            .setContentText("语音助手待命，说「$wake」唤醒")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        nm.notify(NOTIF_ID, notif)
        Dbg.log("蓝牙自动启动：已通知「已连接 $deviceName」")
    }

    /**
     * 检查蓝牙是否可用、是否已授权。
     *
     * 给设置页显示状态用。
     */
    fun status(context: Context): String {
        val keyword = Prefs.bluetoothTargetName.trim()
        return when {
            keyword.isBlank() -> "未配置目标设备"
            !hasPermission(context) -> "缺蓝牙权限"
            !isBluetoothOn() -> "蓝牙未开启"
            else -> "监听「$keyword」"
        }
    }

    /** Android 12+ 需要 BLUETOOTH_CONNECT 才能读设备名 */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothOn(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 列出已配对设备，方便用户填关键词。
     *
     * 设置页「查看已配对设备」按钮用。
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<String> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { dev ->
                val name = try { dev.name } catch (e: SecurityException) { null }
                "${name ?: dev.address}  ${dev.address}"
            }.sorted()
        } catch (e: SecurityException) {
            Dbg.log("蓝牙自动启动：读已配对设备被拒 ${e.message}")
            emptyList()
        }
    }
}
