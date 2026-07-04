package com.huiyi.app.permissions

data class AppPermissionStatus(
    val microphoneEnabled: Boolean,
    val notificationEnabled: Boolean,
    val fileAccessEnabled: Boolean
)
