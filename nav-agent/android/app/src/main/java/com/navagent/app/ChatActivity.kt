package com.navagent.app

import android.Manifest
import android.content.Intent
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.navagent.core.ChatMessage

/**
 * 主界面：AI 聊天。
 *
 * 导航不是主界面 —— 它是聊天里的一句话"导航去张掖西站"打开的一个页面。
 * 所以这里只管对话：打字、语音、看历史。
 *
 * 语音链路在 [VoiceService] 里常驻，本页只观察状态。
 */
class ChatActivity : AppCompatActivity(), NavApp.ChatUi, NavApp.VoiceUi {

    private val app: NavApp get() = application as NavApp

    private lateinit var recycler: RecyclerView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var adapter: MsgAdapter

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            startVoice()
        } else {
            status.text = "没有麦克风权限，只能打字"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        recycler = findViewById(R.id.messages)
        input = findViewById(R.id.input)
        status = findViewById(R.id.voice_status)

        adapter = MsgAdapter().apply {
            items.addAll(app.chat.snapshot())
        }
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter.setHasStableIds(true)
        recycler.adapter = adapter
        scrollToBottom()

        findViewById<Button>(R.id.send).setOnClickListener { submitInput() }
        findViewById<Button>(R.id.mic).setOnClickListener { onMic() }
        findViewById<TextView>(R.id.open_nav).setOnClickListener { app.openNavPage() }
        findViewById<TextView>(R.id.interrupt_nav).setOnClickListener {
            app.nav?.stop()
            findViewById<TextView>(R.id.interrupt_nav).visibility = View.GONE
            findViewById<TextView>(R.id.open_nav).setText(R.string.open_nav)
            status.text = "导航已中断 · 目的地已保留"
        }
        findViewById<TextView>(R.id.settings).setOnClickListener { SettingsActivity.start(this) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput(); true
            } else false
        }

        requestPermissionsIfNeeded()
        consumeSayExtra(intent)
    }

    /**
     * 让列表跟 [NavApp.chat] 的历史对齐。
     *
     * 为什么要这个：设置页能清空历史，清完返回时列表必须跟着空掉。
     * 空历史时补一条开场白，别让界面光秃秃的。
     */
    private fun syncMessages() {
        val snap = app.chat.snapshot()
        val wantCount = if (snap.isEmpty()) 1 else snap.size
        if (wantCount == adapter.itemCount) return

        adapter.streaming = false
        adapter.items.clear()
        if (snap.isEmpty()) {
            // 唤醒词可能被用户改过，开场白跟着变
            val wake = Prefs.primaryWakeWord()
            adapter.items += ChatMessage(
                ChatMessage.Role.ASSISTANT,
                getString(R.string.chat_greeting, wake)
            )
        } else {
            adapter.items.addAll(snap)
        }
        adapter.notifyDataSetChanged()
        scrollToBottom()
    }

    /**
     * 调试入口：跳过语音，直接把一句话喂给大脑，用来验证「聊天 → 导航」链路。
     *
     *   adb shell am start -n com.navagent/com.navagent.app.ChatActivity --es say "导航去张掖西站"
     */
    private fun consumeSayExtra(intent: Intent?) {
        val say = intent?.getStringExtra(EXTRA_SAY)?.takeIf { it.isNotBlank() } ?: return
        Dbg.log("consumeSayExtra: $say")
        window.decorView.postDelayed({ app.submit(say) }, 600)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Dbg.log("onNewIntent extras=${intent?.extras?.keySet()?.toList()}")
        if (intent == null) return
        setIntent(intent)
        consumeSayExtra(intent)
    }

    private fun requestPermissionsIfNeeded() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.ACCESS_COARSE_LOCATION

        if (need.isEmpty()) startVoice() else permLauncher.launch(need.toTypedArray())
    }

    private fun startVoice() {
        runCatching { VoiceService.start(this) }
            .onFailure { status.text = "语音服务启动失败：${it.message}" }
    }

    private fun submitInput() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        app.submit(text)
    }

    private fun onMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        VoiceService.start(this)
        VoiceService.wake(this)
        status.text = "在听…"
    }

    private fun scrollToBottom() {
        recycler.post {
            if (adapter.itemCount > 0) recycler.scrollToPosition(adapter.itemCount - 1)
        }
    }

    // ---------------------------------------------------------------- 观察者

    override fun onUser(text: String) {
        runOnUiThread {
            adapter.items += ChatMessage(ChatMessage.Role.USER, text)
            adapter.notifyItemInserted(adapter.itemCount - 1)
            scrollToBottom()
        }
    }

    override fun onAssistantDelta(text: String) {
        runOnUiThread {
            val last = adapter.items.lastOrNull()
            if (last != null && last.role == ChatMessage.Role.ASSISTANT && adapter.streaming) {
                adapter.items[adapter.itemCount - 1] = last.copy(text = last.text + text)
                adapter.notifyItemChanged(adapter.itemCount - 1)
            } else {
                adapter.items += ChatMessage(ChatMessage.Role.ASSISTANT, text)
                adapter.streaming = true
                adapter.notifyItemInserted(adapter.itemCount - 1)
            }
            scrollToBottom()
        }
    }

    override fun onAssistantDone(full: String) {
        runOnUiThread {
            adapter.streaming = false
            if (adapter.itemCount > 0) {
                val i = adapter.itemCount - 1
                adapter.items[i] = adapter.items[i].copy(text = full)
                adapter.notifyItemChanged(i)
            }
            scrollToBottom()
        }
    }

    override fun onVoiceState(state: VoiceEngine.State, hint: String) {
        runOnUiThread {
            status.text = hint
            updateMicIndicator(state)
        }
    }

    /**
     * 语音按钮的状态反馈：聆听时放大缩小地"呼吸"，同时换成高亮描边样式。
     *
     * 为什么要动：纯靠底下那行小字，开车时根本注意不到是不是在听。
     * 按钮自己会动，余光就能看见。
     */
    private fun updateMicIndicator(state: VoiceEngine.State) {
        val mic = findViewById<Button>(R.id.mic) ?: return
        when (state) {
            VoiceEngine.State.LISTENING -> {
                mic.text = getString(R.string.mic_listening)
                mic.alpha = 1f
                // ★ 用对话页专用的明亮聆听样式（bg_mic_listening）。
                //   不能用导航页那个 bg_btn_listening —— 那个是「深底+描边」，
                //   为了跟导航页统一后的输入框底色配套，套到对话页的深色背景上
                //   几乎看不见（用户反馈「聆听中是暗色，显示不明显」）。
                mic.setBackgroundResource(R.drawable.bg_mic_listening)
                // 亮底必须配深字，不然白字压在亮蓝上反而看不清
                mic.setTextColor(android.graphics.Color.parseColor("#0E1620"))
                startMicPulse(mic)
            }

            VoiceEngine.State.THINKING -> {
                mic.text = getString(R.string.mic_thinking)
                mic.setBackgroundResource(R.drawable.bg_btn_primary)
                mic.setTextColor(android.graphics.Color.parseColor("#0E1620"))
                stopMicPulse(mic)
            }

            VoiceEngine.State.ASLEEP -> {
                mic.text = "◉ 语音"
                mic.setBackgroundResource(R.drawable.bg_btn_primary)
                mic.setTextColor(android.graphics.Color.parseColor("#0E1620"))
                stopMicPulse(mic)
            }
        }
    }

    private var micPulse: ObjectAnimator? = null

    /** 聆听状态的专用声波徽标，尺寸固定，不缩放按钮本身。 */
    private fun startMicPulse(v: View) {
        if (micPulse?.isRunning == true) return
        micPulse = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.72f).apply {
            duration = 680
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopMicPulse(v: View) {
        micPulse?.cancel()
        micPulse = null
        v.alpha = 1f
    }

    override fun onNotice(text: String) {
        runOnUiThread { status.text = text }
    }

    override fun onResume() {
        super.onResume()
        app.addChatUi(this)
        app.addVoiceUi(this)
        app.refreshVoiceUi(this)
        // 名字可在设置里改，回页面就刷成当前值
        findViewById<TextView>(R.id.ai_title).text = Prefs.aiName
        syncMessages()
        val navigating = app.nav?.isNavigating == true
        findViewById<TextView>(R.id.open_nav).setText(
            if (navigating) R.string.open_nav_active else R.string.open_nav
        )
        findViewById<TextView>(R.id.interrupt_nav).visibility =
            if (navigating) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        app.removeChatUi(this)
        app.removeVoiceUi(this)
    }

    private companion object {
        const val EXTRA_SAY = "say"
    }

    // ---------------------------------------------------------------- 列表

    private class MsgAdapter : RecyclerView.Adapter<MsgAdapter.VH>() {

        val items = mutableListOf<ChatMessage>()

        /** 正在流式输出最后一条 */
        var streaming = false

        override fun getItemId(position: Int): Long = items[position].ts + position

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_msg, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            val mine = m.role == ChatMessage.Role.USER
            holder.row.gravity = if (mine) Gravity.END else Gravity.START
            holder.bubble.text = m.text
            holder.bubble.setBackgroundResource(
                if (mine) R.drawable.bg_bubble_user else R.drawable.bg_bubble_ai
            )
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val row: android.widget.LinearLayout = v.findViewById(R.id.row)
            val bubble: TextView = v.findViewById(R.id.bubble)
        }
    }
}
