package com.navagent.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络可用性判断。
 *
 * ★ 为什么不用 `activeNetworkInfo.isConnected`：
 *   那个只说明「连上了某个网络」，不说明这个网络能上外网。
 *   车上很常见的场景是手机连了车机热点，但热点没流量 ——
 *   isConnected 返回 true，请求照样超时，用户还是听不到回答。
 *
 *   NET_CAPABILITY_VALIDATED 是系统真正探测过外网之后才打的标，
 *   用它才能区分「连了 WiFi」和「能上网」。
 */
object NetworkState {

    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
