package com.android.espauto

import android.content.Context
import android.widget.Toast

object ToastUtil {
    private var currentToast: Toast? = null

    fun show(context: Context?, text: CharSequence) {
        context ?: return
        currentToast?.cancel()
        currentToast = Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT)
        currentToast?.show()
    }

}