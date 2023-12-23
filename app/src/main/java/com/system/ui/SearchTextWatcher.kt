package com.system.ui

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher

/**
 * 监听文本变化的简单SearchTextWatcher
 * Created by Hello on 2018/03/04.
 */


public class SearchTextWatcher(private var onChange: Runnable) : TextWatcher {
    private val myHandler = Handler(Looper.getMainLooper())
    var lastInput = 0L
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        s?.run {
            val current = System.currentTimeMillis()
            lastInput = current
            myHandler.postDelayed({
                if (lastInput == current) {
                    onChange.run()
                }
            }, 300)
        }
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
    }

    override fun afterTextChanged(s: Editable?) {
    }
}