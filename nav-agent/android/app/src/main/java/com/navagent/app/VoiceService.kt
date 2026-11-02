package com.navagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.navagent.core.VoiceGuide

/**
 * 语音前台服务。
 *
 * 为什么必须有它：
 *  1. 麦克风只能有一个持有者，而导航页和聊天页都要用 → 只能放到服务里当单例
 *  2. 息屏/切后台时 Activity 会被暂停，但唤醒词必须一直能听见
 *  3. Android 14+ 后台用麦克风必须带 foregroundServiceType=microphone
 *
 * 注意：这个服务只管"听"，不管"说"。TTS 在 Application 里（VoiceGuide 单例），
 * 因为导航播报和聊天回复共用同一条队列。
 */
class VoiceService : Service() {

    inner class LocalBinder : Binder() {
        fun engine(): VoiceEngine = this@VoiceService.engine
        fun service(): VoiceService = this@VoiceService
    }

    private val binder = LocalBinder()

    lateinit var engine: VoiceEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = VoiceEngine(this)
        (application as NavApp).voiceStateSnapshotProvider = { engine.stateSnapshot() }
        // 引擎引用挂到 Application 上，工具层（主动休眠）要用。
        // 有了这个就不用绕 ACTION_SLEEP 那条 Intent 通路，也不用等 Intent 派发。
        (application as NavApp).voiceEngineProvider = { engine }
        // 声纹门禁：每次用时从 Application 取（模型可能是后台下载完才挂上的）
        engine.voiceprintProvider = { (application as NavApp).voiceprintGate }

        // 播报中用户开口 → 停播报
        engine.onBargeIn = {
            (application as NavApp).voice.silence()
        }
        engine.isSpeakingProvider = {
            val v = (application as NavApp).voice
            // 播报中 + 播报刚结束的回声尾巴，都不拾音（半双工 + 回声保护期）。
            // 保护期长度在设置页可调：车内音响好、混响长就调大。
            v.isSpeaking || v.inEchoWindow(Prefs.echoGuardMs)
        }

        // 睡眠口令本地命中：不经过 LLM，直接播一句「好」再休眠。
        //
        // 为什么给起始守卫：云端 TTS 是「请求 → 合成 → 播放」，speak() 返回时
        // isSpeaking 还是 false，要几百毫秒才变 true。不给守卫的话
        // sleepAfterSpeaking 会立刻判定"没在播"，800ms 后就睡了，「好」被截断。
        engine.onSleepCommand = {
            val app = application as NavApp
            app.voice.speak("好", VoiceGuide.Priority.USER)
            engine.sleepAfterSpeaking(SLEEP_SPEAK_GUARD_MS)
        }

        // 识别的结果统一从这里进大脑，Activity 只是观众
        engine.setListener(object : VoiceEngine.Listener {
            override fun onState(state: VoiceEngine.State, hint: String) {
                (application as NavApp).dispatchVoiceState(state, hint)
            }

            override fun onUtterance(text: String) {
                (application as NavApp).submit(text)
            }

            override fun onNotice(text: String) {
                (application as NavApp).dispatchNotice(text)
            }
        })
        startForegroundSafely()
        engine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WAKE -> engine.wakeManually()
            ACTION_SLEEP -> engine.sleep()
            // 设置页改完唤醒词 / 重下模型后走这里
            ACTION_RELOAD_WAKE -> engine.reloadWake()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "语音待命", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "保持唤醒词常驻监听"
                    setShowBadge(false)
                }
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 唤醒词可配，通知里别写死「小高」
        val wake = Prefs.primaryWakeWord()
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("语音待命")
            .setContentText("说「$wake」唤醒，点这里回到对话")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 必须同时声明 location：导航在后台时高德定位会报 code=13
            // 「后台运行没有后台定位权限」（实测），带上 location 类型就不掉了。
            runCatching {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            }.onFailure {
                Dbg.log("startForeground(location) 失败，退回 microphone：${it.message}")
                runCatching {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val CHANNEL_ID = "voice_standby"
        private const val NOTIF_ID = 1001
        const val ACTION_WAKE = "com.navagent.WAKE"
        const val ACTION_SLEEP = "com.navagent.SLEEP"
        const val ACTION_RELOAD_WAKE = "com.navagent.RELOAD_WAKE"

        /**
         * 本地睡眠口令命中后，等 TTS 真正开始播的守卫时间。
         *
         * 云端合成通常 300~800ms 才出声，给到 1.5 秒留足余量；
         * 就算 TTS 失败，1.5 秒后也会正常走休眠，不会卡住。
         */
        private const val SLEEP_SPEAK_GUARD_MS = 1_500L

        fun start(context: Context) {
            val i = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun wake(context: Context) {
            context.startService(
                Intent(context, VoiceService::class.java).setAction(ACTION_WAKE)
            )
        }

        /** 唤醒词改了 / 模型换了，让引擎重建识别器 */
        fun reloadWake(context: Context) {
            context.startService(
                Intent(context, VoiceService::class.java).setAction(ACTION_RELOAD_WAKE)
            )
        }

        /**
         * 彻底停掉语音常驻服务。
         *
         * 给导航页左上角的「退出应用」用：光 finish Activity 的话，
         * 服务还活着 —— 通知栏那条「语音待命」留着、麦克风还开着、
         * 唤醒词还在响应，用户会以为没退干净。
         *
         * `stopService` 是显式停止，不受 START_STICKY 影响（那条只管进程被系统杀掉后重建）。
         * 停止后 `onDestroy → engine.stop()` 会释放 AudioRecord。
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
