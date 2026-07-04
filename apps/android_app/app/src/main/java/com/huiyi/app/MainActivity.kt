package com.huiyi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0xFFF4F7FC.toInt(), 0xFFF4F7FC.toInt()),
            navigationBarStyle = SystemBarStyle.light(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        )
        setContent { HuixiaoApp() }
    }
}
