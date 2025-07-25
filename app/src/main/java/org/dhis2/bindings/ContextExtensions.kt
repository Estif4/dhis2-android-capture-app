package org.dhis2.bindings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.dhis2.BuildConfig

fun Context.buildInfo(): String {
    return if (BuildConfig.BUILD_TYPE == "release") {
        "v${BuildConfig.VERSION_NAME}"
    } else {
        "v${BuildConfig.VERSION_NAME} : ${BuildConfig.GIT_SHA} "
    }
}

fun Fragment.checkSMSPermission(requestPermission: Boolean, request: Int): Boolean {
    val smsPermissions = arrayOf(
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
    )

    if (context?.hasPermissions(smsPermissions) != true) {
        if (requestPermission) {
            requestPermissions(smsPermissions, request)
        }
        return false
    }
    return true
}

fun Context.hasPermissions(permissions: Array<String>): Boolean {
    for (permission in permissions) {
        if (ContextCompat.checkSelfPermission(this, permission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
    }
    return true
}
