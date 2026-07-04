package com.huiyi.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class AndroidNetworkStatus(context: Context) {
    private val appContext = context.applicationContext

    fun isNetworkAvailable(): Boolean? {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
