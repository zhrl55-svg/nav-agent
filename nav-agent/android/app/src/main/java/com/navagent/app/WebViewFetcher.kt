package com.navagent.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 离屏 WebView 取网页正文。
 *
 * ## 为什么需要它
 *
 * Tavily extract 走的是服务端 HTTP 抓取，遇到**靠 JS 渲染的页面**只会拿到空壳：
 * 微信公众号、知乎、部分新闻站、几乎所有 SPA 站都是这样。
 * MiniMax 那个 `/v1/coding_plan/search` 更狠，只回 title/snippet/link，不含正文。
 *
 * 手机上本来就有系统 WebView（和本机浏览器同一个内核），
 * 让它把页面渲染出来再取 `innerText`，就能拿到真人看到的内容 —— 不用装任何东西。
 *
 * ## 关键约束（都是踩过的坑）
 *
 * 1. **WebView 必须在主线程创建**（loadUrl / evaluateJavascript 同理），
 *    而工具是跑在子线程里的 —— 所以这里用 Handler 切回主线程，
 *    子线程用 CountDownLatch 阻塞等待结果。
 * 2. **evaluateJavascript 的回调返回的是 JSON 编码后的字符串**（`"abc\n"`），
 *    不是裸文本。直接用会多出一层引号和转义符，必须用 JSONTokener 解包。
 * 3. **不要把 WebView attach 到窗口**：不 attach 也能正常渲染 + 执行 JS，
 *    而且不会在用户屏幕上闪出一下页面。
 * 4. **onPageFinished 不等于内容渲染完了**：SPA 和懒加载站点此时 innerText 还是空的。
 *    所以要隔一小段时间再取，并且允许重试几次。
 * 5. **destroy 也要在主线程**，且不能早于 evaluateJavascript 的回调，
 *    否则会拿到空串并抛异常。
 * 6. **Kotlin 2.x 不允许引用捕获了变量的局部函数**（`::localFun` 报
 *    "References to variables aren't supported yet"），所以状态收在 [Job] 里，
 *    回调是 object 的成员方法而不是局部函数。
 *
 * ## 失败怎么办
 *
 * 返回空字符串，由调用方降级到 Tavily / 兜底搜索。
 * 这里不抛异常 —— 工具层的约定是「失败也要返回一句人能读懂的话」。
 */
object WebViewFetcher {

    /** 主线程 Handler。整个进程共用一个即可。 */
    private val main = Handler(Looper.getMainLooper())

    /**
     * 一次抓取的全部状态。
     *
     * 用它而不是局部变量，是因为回调要跨线程、跨延迟消息传递，
     * 而 Kotlin 2.x 不允许对捕获变量的局部函数取引用。
     */
    private class Job {
        lateinit var url: String
        val box = StringBuilder()
        val attempts = intArrayOf(0)
        val latch = CountDownLatch(1)
        @Volatile var webView: WebView? = null
        @Volatile var done = false
    }

    /**
     * 取正文的主 JS。
     *
     * 优先挑语义容器（article / main / [role=main]），
     * 但如果它太短就退回 body —— 有些站点的 article 只是个空壳。
     */
    private const val JS_EXTRACT = """
(function(){
  try {
    var t = '';
    var m = document.querySelector('article, main, [role="main"], #article, .article-content, .article');
    if (m && m.innerText && m.innerText.trim().length > 80) {
      t = m.innerText;
    } else if (document.body) {
      t = document.body.innerText || '';
    }
    t = (t || '').replace(/ /g, ' ');
    t = t.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
    return t.slice(0, 40000);
  } catch (e) {
    return '';
  }
})()
"""

    /** 桌面版 UA：公众号、知乎这类站对移动 UA 会跳到简化页或要求验证。 */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 抓取并返回正文纯文本。**必须在子线程调用**（会阻塞最多 [timeoutMs]）。
     *
     * @return 正文文本；失败返回空串（不抛异常）
     */
    fun fetch(
        context: Context,
        url: String,
        timeoutMs: Long = 15_000L
    ): String {
        val job = Job().apply { this.url = url }

        main.post {
            try {
                val wv = WebView(context)
                job.webView = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    userAgentString = UA
                    // 图片、字体、视频一律不下载：我们要的是文字
                    setBlockNetworkImage(true)
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        // 内容可能还没渲染出来，隔一会儿再取，取不到就再等
                        scheduleExtract(view, job)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = false

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError
                    ) {
                        // 只关心主框架失败；子资源（图片等）失败无所谓
                        if (request.isForMainFrame) {
                            Dbg.log("webview 加载失败 ${job.url}: ${error.description}")
                            finish(job)
                        }
                    }
                }
                wv.loadUrl(url)
            } catch (t: Throwable) {
                Dbg.log("webview 创建失败 ${t.javaClass.simpleName}: ${t.message}")
                finish(job)
            }
        }

        // 兜底超时：即使 onPageFinished 一直没来，也要把线程放出去
        main.postDelayed({ finish(job) }, timeoutMs)

        val got = job.latch.await(timeoutMs + 2_000L, TimeUnit.MILLISECONDS)
        if (!got) Dbg.log("webview 超时 ${job.url}")
        return job.box.toString()
    }

    /** 结束一次抓取：销毁 WebView（主线程）并放行等待线程。 */
    private fun finish(job: Job) {
        if (job.done) return
        job.done = true
        val wv = job.webView
        if (wv != null) {
            main.post {
                runCatching {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
        }
        job.latch.countDown()
    }

    /**
     * 延迟后取一次正文；太短就再等一轮。
     *
     * 为什么要重试：onPageFinished 只说明 HTML 加载完了，
     * 单页应用的内容是之后由 JS 填进去的。
     */
    private fun scheduleExtract(view: WebView, job: Job) {
        val attempt = job.attempts[0]
        if (attempt >= 3) {
            finish(job)
            return
        }
        main.postDelayed({
            runCatching {
                view.evaluateJavascript(JS_EXTRACT) { raw ->
                    val text = decodeJsString(raw)
                    if (text.length >= 300 || attempt >= 2) {
                        if (text.isNotBlank()) job.box.append(text)
                        finish(job)
                    } else {
                        job.attempts[0] = attempt + 1
                        scheduleExtract(view, job)
                    }
                }
            }.onFailure {
                Dbg.log("webview evaluate 失败 ${it.javaClass.simpleName}")
                finish(job)
            }
        }, 500L + attempt * 700L)
    }

    /**
     * 解开 evaluateJavascript 的返回值。
     *
     * 回调拿到的是 JSON 编码后的字符串字面量（`"line1\nline2"`），
     * 直接当正文用会带上引号和 `\n` 转义符。
     */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            when (val v = JSONTokener(raw).nextValue()) {
                is String -> v
                is org.json.JSONObject -> v.optString("value", "")
                else -> raw
            }
        } catch (t: Throwable) {
            // 不是合法 JSON（极少见），退而求其次去掉首尾引号
            raw.trim().removeSurrounding("\"").replace("\\n", "\n")
        }
    }
}
