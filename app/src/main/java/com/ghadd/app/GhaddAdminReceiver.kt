package com.ghadd.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class GhaddAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
}
