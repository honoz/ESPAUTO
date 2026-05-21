/*
 * ESPAUTO
 * Copyright (c) 2026 honoz
 * Licensed under the MIT License.
 */

package com.android.espauto

import android.content.Context
import android.widget.Toast

object ToastUtil {
    // 静态持有全局单例 Toast 代理引用，避免高频弹出提示时在底层创建大量的通知窗体排队
    private var currentToast: Toast? = null

    fun show(context: Context?, text: CharSequence) {
        context ?: return
        // 瞬间灭活上一个正在屏幕渲染的悬浮窗，从而实现新通知能立马打断并覆盖旧通知的连发视觉效果
        currentToast?.cancel()
        // 必须注入 applicationContext，防止持有临时 Activity 上下文导致窗口泄露或长周期内存死锁
        currentToast = Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT)
        currentToast?.show()
    }

    fun cancel() {
        currentToast?.cancel()
        currentToast = null
    }
}