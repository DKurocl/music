# Android system APIs

Source audit 2026-11-13. Kotlin fragments require imports, application state and lifecycle wiring. No device or compiler run. Sideloaded apps still use the system grants listed here.

## Quick Settings

verified: ні

[docs] Service exported with BIND_QUICK_SETTINGS_TILE and android.service.quicksettings.action.QS_TILE. No runtime grant for tile itself. Use unlockAndRun for protected actions and PendingIntent overload for launch. No API 37 change identified.

https://developer.android.com/develop/ui/views/quicksettings-tiles

[аналіз] Integration fragment:

```kotlin
override fun onStartListening() {
    qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
}
```

## Glance and lock screen

verified: ні

[docs] Register GlanceAppWidgetReceiver and android.appwidget.provider XML with initialLayout. No runtime grant. Android 17 rejects oversized target-37 RemoteViews updates. Budget estimates use 6 bytes per display pixel across distinct bitmaps in all size buckets. Prefer resource images and verify dumpsys appwidget.

https://developer.android.com/about/versions/17/behavior-changes-17#widget-memory-limit

[аналіз] Integration fragment:

```kotlin
val opts = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
val keyguard = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, 0) ==
    AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD
```

## Bluetooth RFCOMM

verified: ні

[docs] Declare/request BLUETOOTH_CONNECT, plus BLUETOOTH_SCAN only for discovery. Handle IOException on older releases and EOF on target 37. Run blocking reads off main and close socket on cancellation.

https://developer.android.com/about/versions/17/behavior-changes-17

[аналіз] Integration fragment:

```kotlin
val buffer = ByteArray(4096)
while (true) {
    val count = socket.inputStream.read(buffer)
    if (count < 0) break
    if (count > 0) consume(buffer.copyOf(count))
}
```

## Companion device presence

verified: ні

[docs] Use system association UI. Association does not establish transport. Declare REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE, service bind permission BIND_COMPANION_DEVICE_SERVICE and action android.companion.CompanionDeviceService. Use ID request on 36+, legacy address path on 35. Stop observing when disabled.

https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing

[аналіз] Integration fragment:

```kotlin
if (Build.VERSION.SDK_INT >= 36) {
    manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder()
        .setAssociationId(association.id).build())
}
```

## VPN

verified: ні

[docs] Service protected by BIND_VPN_SERVICE with android.net.VpnService action. Declare INTERNET for transport, FOREGROUND_SERVICE and matching type/permission for active VPN. Confirm system consent, protect transport socket, close TUN on revoke. No target 37 VPN-specific change established.

https://developer.android.com/develop/connectivity/vpn

[аналіз] Integration fragment:

```kotlin
val consent = VpnService.prepare(activity)
if (consent != null) vpnConsentLauncher.launch(consent) else startVpn()
```

## Live Updates

verified: ні

[docs] Declare POST_NOTIFICATIONS and POST_PROMOTED_NOTIFICATIONS. Request runtime notification grant. Use ongoing notification with title, supported style and channel above MIN. Inspect posted notification via activeNotifications or dumpsys. Promotion is decided by system. ProgressStyle API 36; native builder promotion method API 36.1, see https://developer.android.com/reference/android/app/Notification.Builder#setRequestPromotedOngoing(boolean).

https://developer.android.com/develop/ui/views/notifications/live-update

[аналіз] Integration fragment:

```kotlin
if (Build.VERSION.SDK_INT >= 36) {
    builder.setStyle(Notification.ProgressStyle().setProgress(progress))
    if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
        builder.setRequestPromotedOngoing(true)
    }
}
```

## Exact alarms

verified: ні

[docs] Declare SCHEDULE_EXACT_ALARM for user-granted special access or USE_EXACT_ALARM for dedicated alarm use. Use explicit immutable receiver PendingIntent. Restore after reboot when needed. Target 37 adds background audio restrictions even if an alarm is delivered.

https://developer.android.com/develop/background-work/services/alarms

[аналіз] Integration fragment:

```kotlin
if (alarmManager.canScheduleExactAlarms()) {
    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, alarmIntent)
} else {
    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
}
```

## Background location

verified: ні

[docs] Declare ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION as needed and ACCESS_BACKGROUND_LOCATION only if essential. Request foreground first, then explain and send to app settings for Allow all the time. For active location FGS, declare type location and relevant FGS permissions. Approximate grant applies in background too.

https://developer.android.com/develop/sensors-and-location/location/permissions/background

[аналіз] Integration fragment:

```kotlin
val granted = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
    PackageManager.PERMISSION_GRANTED
```

## AppFunctions

verified: ні

[docs] Opt-in. Use androidx.appfunctions 1.0.0-rc01 and matching KSP compiler. Check generated assets/merged manifest and service entry point against versioned docs. Authorized caller requires EXECUTE_APP_FUNCTIONS. Gemini invocation of sideload build unconfirmed.

https://developer.android.com/ai/appfunctions

[аналіз] Integration fragment:

```kotlin
@AppFunctionDeclaration(isDescribedByKDoc = true)
suspend fun noteCount(): Int = repository.count()
```

## Shizuku

verified: ні

[docs] Opt-in. Use upstream provider declaration and dependency permissions only in enabled builds. Require running binder and explicit user authorization. Handle binder death and denial. API 37 sideload combination unconfirmed.

https://github.com/RikkaApps/Shizuku-API

[аналіз] Integration fragment:

```kotlin
if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
    Shizuku.requestPermission(101)
}
```

## Local network

verified: ні

[docs] Declare INTERNET and ACCESS_LOCAL_NETWORK for broad LAN access on target 37. Request on API 37 before LAN calls. Use a documented system-mediated picker when appropriate. Handle denial and revocation. Sideload does not grant access.

https://developer.android.com/privacy-and-security/local-network-permission

[аналіз] Integration fragment:

```kotlin
if (Build.VERSION.SDK_INT >= 37 &&
    checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
    requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 102)
}
```
