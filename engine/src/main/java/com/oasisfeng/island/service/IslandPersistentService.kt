package com.oasisfeng.island.service

import android.annotation.SuppressLint
import android.app.Service
import android.app.admin.DeviceAdminService
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.*
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.oasisfeng.island.PersistentService
import com.oasisfeng.island.util.UserPrefixedLog as Log
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

/**
 * Persistent helper service.
 */
@RequiresApi(Build.VERSION_CODES.O)
class IslandPersistentService : DeviceAdminService() {

    companion object {
        private const val TAG = "Island.PS"
        private val CAMERA_ACTIONS = setOf(
            MediaStore.ACTION_IMAGE_CAPTURE,
            MediaStore.ACTION_IMAGE_CAPTURE_SECURE,
            MediaStore.ACTION_VIDEO_CAPTURE,
            MediaStore.Audio.Media.RECORD_SOUND_ACTION,
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE,
            MediaStore.INTENT_ACTION_VIDEO_CAMERA,
            Intent.ACTION_CAMERA_BUTTON,
            "android.media.action.STILL_IMAGE_CAMERA",
            "android.media.action.VIDEO_CAMERA",
            "android.media.action.IMAGE_CAPTURE",
            "android.media.action.VIDEO_CAPTURE"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (isCameraIntent(it)) {
                handleCameraIntent(it)
            }
        }
        return START_STICKY
    }

    private fun isCameraIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false

        // Check action
        if (CAMERA_ACTIONS.contains(action)) {
            return true
        }

        // Check categories
        val categories = intent.categories
        if (categories != null) {
            for (category in categories) {
                if (category?.lowercase(Locale.getDefault())?.contains("camera") == true) {
                    return true
                }
            }
        }

        // Check component
        val component = intent.component
        if (component != null) {
            val packageName = component.packageName?.lowercase(Locale.getDefault()) ?: ""
            val className = component.className?.lowercase(Locale.getDefault()) ?: ""
            if (packageName.contains("camera") || className.contains("camera")) {
                return true
            }
        }

        // Check extras
        if (intent.hasExtra(MediaStore.EXTRA_OUTPUT) ||
            intent.hasExtra(MediaStore.EXTRA_VIDEO_QUALITY) ||
            intent.hasExtra(MediaStore.EXTRA_SIZE_LIMIT) ||
            intent.hasExtra(MediaStore.EXTRA_DURATION_LIMIT)) {
            return true
        }

        return false
    }

    private fun handleCameraIntent(originalIntent: Intent) {
        Log.d(TAG, "Intercepting camera intent: ${originalIntent.action}")

        try {
            val redirectedIntent = createRedirectedIntent(originalIntent)
            redirectedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(redirectedIntent)
            Log.d(TAG, "Successfully redirected camera intent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to redirect camera intent: ${e.message}")
            // Fallback to original intent
            try {
                originalIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(originalIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Also failed to start original camera intent: ${e2.message}")
            }
        }
    }

    private fun createRedirectedIntent(originalIntent: Intent): Intent {
        val redirectedIntent = Intent(originalIntent)

        // Get user-selected camera app from preferences
        val prefs = getSharedPreferences("island_prefs", MODE_PRIVATE)
        val packageName = prefs.getString("camera_replacement_package", null)
        val activityName = prefs.getString("camera_replacement_activity", null)

        if (packageName != null && activityName != null) {
            redirectedIntent.component = ComponentName(packageName, activityName)
            Log.d(TAG, "Redirecting to user-selected camera: $packageName/$activityName")
        } else {
            Log.d(TAG, "No user-selected camera replacement found, using original intent")
            return originalIntent
        }

        // Add identification extras
        redirectedIntent.putExtra("ISLAND_REDIRECTED", true)
        redirectedIntent.putExtra("ORIGINAL_CALLER_PACKAGE", originalIntent.`package`)
        redirectedIntent.putExtra("REDIRECTION_TIMESTAMP", System.currentTimeMillis())

        return redirectedIntent
    }

    override fun onCreate() {
        Log.d(TAG, "Initializing persistent services...")
        Looper.getMainLooper().queue.addIdleHandler(mInitializer)
    }

    private fun bindPersistentServices(pkg: String? = null) {
        val intent = Intent(PersistentService.SERVICE_INTERFACE).setPackage(pkg)
        val uid = Process.myUid()
        val candidates = packageManager.queryIntentServices(intent, 0)
        candidates.forEach {
            if (it.serviceInfo.applicationInfo.uid == uid) bindPersistentService(it.serviceInfo)
        }
    }

    private fun bindPersistentService(service: ServiceInfo) {
        val component = ComponentName(service.packageName, service.name)
        val componentName = component.flattenToShortString()
        Log.i(TAG, "Starting persistent service: $componentName")

        val connection = PersistentServiceConnection(component)
        mConnections.add(connection)
        @SuppressLint("WrongConstant")
        val result = bindService(
            Intent(PersistentService.SERVICE_INTERFACE).setComponent(component),
            connection,
            BIND_AUTO_CREATE or BIND_NOT_FOREGROUND or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) BIND_INCLUDE_CAPABILITIES else 0)
        )
        if (!result) Log.e(TAG, "Failed to start persistent service: $componentName")
    }

    override fun onDestroy() {
        Looper.getMainLooper().queue.removeIdleHandler(mInitializer)
        mConnections.forEach {
            try {
                unbindService(it)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Error disconnecting ${it.mComponent}", e)
            }
        }
    }

    override fun unbindService(conn: ServiceConnection) = super.unbindService(conn).also {
        if (conn is PersistentServiceConnection) Log.i(
            TAG,
            "Stopping persistence service: ${conn.mComponent.flattenToShortString()}"
        )
    }

    private val mInitializer = MessageQueue.IdleHandler { false.also { bindPersistentServices() } }
    private val mConnections = ArrayList<PersistentServiceConnection>()

    private inner class PersistentServiceConnection(val mComponent: ComponentName) : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            Log.i(TAG, "Connected: ${component.flattenToShortString()}")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "Disconnected: ${name.flattenToShortString()}")
        }

        override fun onNullBinding(name: ComponentName) {
            Log.i(TAG, "Quited: ${name.flattenToShortString()}")
            mConnections.remove(this)
            unbindService(this)
        }
    }
}
