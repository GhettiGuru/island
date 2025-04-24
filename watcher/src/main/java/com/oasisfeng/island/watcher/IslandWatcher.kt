package com.oasisfeng.island.watcher

import android.app.Notification
import android.app.Notification.CATEGORY_PROGRESS
import android.app.Notification.CATEGORY_STATUS
import android.app.Notification.VISIBILITY_PUBLIC
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.DONT_KILL_APP
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.P
import android.os.Build.VERSION_CODES.Q
import android.os.IBinder
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import androidx.core.graphics.createBitmap
import com.oasisfeng.android.widget.Toasts
import com.oasisfeng.island.IslandNameManager
import com.oasisfeng.island.home.HomeRole
import com.oasisfeng.island.notification.NotificationIds
import com.oasisfeng.island.notification.post
import com.oasisfeng.island.shuttle.Shuttle
import com.oasisfeng.island.util.DPM
import com.oasisfeng.island.util.DevicePolicies
import com.oasisfeng.island.util.OwnerUser
import com.oasisfeng.island.util.Users
import com.oasisfeng.island.util.Users.Companion.ACTION_USER_INFO_CHANGED
import com.oasisfeng.island.util.Users.Companion.EXTRA_USER_HANDLE
import com.oasisfeng.island.util.Users.Companion.toId
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Watch recently started managed-profile and offer action to stop.
 *
 * Created by Oasis on 2019-2-25.
 */
@RequiresApi(P) class IslandWatcher : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		Log.d(TAG, "onReceive: $intent")
		if (intent.action !in listOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED,
				Intent.ACTION_MY_PACKAGE_REPLACED, NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED,
				NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED, ACTION_USER_INFO_CHANGED))
			return
		if (Users.isParentProfile()) return context.packageManager.setComponentEnabledSetting(ComponentName(context, javaClass),
				COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP)
		val policies = DevicePolicies(context)
		if (! policies.isProfileOwner) return
		if (intent.action == ACTION_USER_INFO_CHANGED
			&& intent.getIntExtra(EXTRA_USER_HANDLE, Users.NULL_ID) != Users.currentId()) return
		if (NotificationIds.IslandWatcher.isBlocked(context)) return

		if (context.getSystemService<UserManager>()?.isUserUnlocked == false) return    // Not unlocked yet
//		val canDeactivate = if (SDK_INT >= Q) isParentProfileOwner(context)
//			else context.getSystemService(LauncherApps::class.java)!!.hasShortcutHostPermission()   // hasShortcutHostPermission() below throws IllegalStateException: "User N is locked or not running"
		val canRestart = policies.isManagedProfile && ! policies.invoke(DPM::isUsingUnifiedPassword)
				&& policies.manager.storageEncryptionStatus == ENCRYPTION_STATUS_ACTIVE_PER_USER

		NotificationIds.IslandWatcher.post(context) {
			setOngoing(true).setGroup(GROUP).setGroupSummary(true).setCategory(CATEGORY_STATUS).setVisibility(VISIBILITY_PUBLIC)
			setSmallIcon(com.oasisfeng.island.shared.R.drawable.ic_landscape_black_24dp)
			setLargeIcon(Icon.createWithBitmap(getAppIcon(context)))
			setColor(context.getColor(com.oasisfeng.island.shared.R.color.primary))
			setContentTitle(context.getString(R.string.notification_island_watcher_title, IslandNameManager.getName(context)))
			setContentText(context.getText(if (canRestart) R.string.notification_island_watcher_text_for_restart
				else R.string.notification_island_watcher_text_for_deactivate))
			if (canRestart) addServiceAction(context, R.string.action_restart_island, Intent.ACTION_REBOOT)
			addServiceAction(context, R.string.action_deactivate_island)
			addAction(Notification.Action.Builder(null, context.getText(R.string.action_settings), PendingIntent.getActivity(context, 0,
				NotificationIds.IslandWatcher.buildChannelSettingsIntent(context), FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)).build()) }
	}

	private fun Notification.Builder.addServiceAction(context: Context, @StringRes label: Int, action: String? = null) {
		addAction(Notification.Action.Builder(null, context.getText(label), PendingIntent.getService(context, 0,
			Intent(context, IslandDeactivationService::class.java).setAction(action), FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)).build())
	}

	private fun getAppIcon(context: Context): Bitmap {
		val size = context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
		return createBitmap(size, size).also { bitmap ->
			context.applicationInfo.loadIcon(context.packageManager).apply {
				setBounds(0, 0, size, size)
				draw(Canvas(bitmap)) }}
	}

	class IslandDeactivationService: Service() {

		@OptIn(DelicateCoroutinesApi::class)
		override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
			val action = intent?.action
			if (action == Intent.ACTION_REBOOT) {
				DevicePolicies(this).manager.lockNow(DevicePolicyManager.FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)
			} else if (SDK_INT >= Q) {      // "Deactivate"
				if (Users.isParentProfile()) { @Suppress("DEPRECATION")
					intent?.getParcelableExtra<UserHandle>(Intent.EXTRA_USER)?.also { profile ->
						GlobalScope.launch { requestQuietModeApi29(this@IslandDeactivationService, profile) }
						return START_STICKY }   // Still ongoing
				} else {
					if (isParentProfileOwner(this) == true) // The automatic way
						Shuttle(this, to = Users.parentProfile).launch(with = Users.current()) {
							startService(Intent(this, IslandDeactivationService::class.java).putExtra(Intent.EXTRA_USER, it)) }
					else try {                              // The manual way
						startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
						Toasts.showLong(applicationContext, R.string.toast_manual_quiet_mode) }
					catch (_: ActivityNotFoundException) { Toasts.showLong(applicationContext, "Sorry, ROM is incompatible.") }
				}
			} else requestQuietMode(Users.current())
			stopSelf()
			return START_NOT_STICKY
		}

		@OwnerUser @RequiresApi(Q) private suspend fun requestQuietModeApi29(context: Context, profile: UserHandle) {
			if (! DevicePolicies(context).isProfileOwner) return startSystemSyncSettings()

			Log.i(TAG, "Preparing to deactivating Island (${profile.toId()})...")

			val successful = HomeRole.runWithHomeRole(context) {
				registerReceiver(object : BroadcastReceiver() { override fun onReceive(c: Context, intent: Intent) { @Suppress("DEPRECATION")
					val user = intent.getParcelableExtra<UserHandle>(Intent.EXTRA_USER)
					if (user != profile) return

					unregisterReceiver(this)
					Log.i(TAG, "Island is deactivated: ${user.toId()}")
				}}, IntentFilter(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE))

				Log.i(TAG, "Deactivating Island ${profile.toId()}...")
				requestQuietMode(profile) }

			Toasts.showShort(context, if (successful) R.string.prompt_island_deactivated else R.string.prompt_failed_deactivating_island)
		}

		private fun requestQuietMode(profile: UserHandle) {
			// requestQuietModeEnabled() requires us running as foreground (service).
			NotificationIds.IslandWatcher.startForeground(this, Notification.Builder(this, null)
				.setSmallIcon(com.oasisfeng.island.shared.R.drawable.ic_landscape_black_24dp)
				.setCategory(CATEGORY_PROGRESS).setColor(getColor(com.oasisfeng.island.shared.R.color.primary))
				.setProgress(0, 0, true).setContentTitle("Deactivating Island space..."))

			try { getSystemService<UserManager>()!!.requestQuietModeEnabled(true, profile) }
			catch (e: SecurityException) {   // Fall-back to manual control
				startSystemSyncSettings().also { Log.d(TAG, "Error deactivating Island ${profile.toId()}", e) }}
			finally { stopForeground(STOP_FOREGROUND_REMOVE) }
		}

		private fun isParentProfileOwner(context: Context)
		= Shuttle(context, to = Users.parentProfile).invokeNoThrows { DevicePolicies(this).isProfileOwner }

		private fun startSystemSyncSettings() {
			try {
				startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
				Toasts.showLong(applicationContext, R.string.toast_manual_quiet_mode) }
			catch (_: ActivityNotFoundException) { Toasts.showLong(applicationContext, "Sorry, ROM is incompatible.") }
		}

		override fun onBind(intent: Intent): IBinder? = null
	}
}

// With shared notification group, app watcher (group child) actually hides Island watcher (group summary), which only shows up if no app watchers.
internal const val GROUP = "Watcher"
private const val TAG = "Island.Watcher"
