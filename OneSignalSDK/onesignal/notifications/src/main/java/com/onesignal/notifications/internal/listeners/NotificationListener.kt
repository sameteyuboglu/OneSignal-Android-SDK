package com.onesignal.notifications.internal.listeners

import android.app.Activity
import com.onesignal.common.JSONUtils
import com.onesignal.common.exceptions.BackendException
import com.onesignal.core.internal.application.AppEntryAction
import com.onesignal.core.internal.application.IApplicationService
import com.onesignal.core.internal.config.ConfigModelStore
import com.onesignal.core.internal.device.IDeviceService
import com.onesignal.core.internal.startup.IStartableService
import com.onesignal.core.internal.time.ITime
import com.onesignal.debug.internal.logging.Logging
import com.onesignal.notifications.internal.INotificationActivityOpener
import com.onesignal.notifications.internal.analytics.IAnalyticsTracker
import com.onesignal.notifications.internal.backend.INotificationBackendService
import com.onesignal.notifications.internal.common.NotificationConstants
import com.onesignal.notifications.internal.common.NotificationFormatHelper
import com.onesignal.notifications.internal.common.NotificationGenerationJob
import com.onesignal.notifications.internal.common.NotificationHelper
import com.onesignal.notifications.internal.common.OSNotificationOpenAppSettings
import com.onesignal.notifications.internal.lifecycle.INotificationLifecycleEventHandler
import com.onesignal.notifications.internal.lifecycle.INotificationLifecycleService
import com.onesignal.notifications.internal.receivereceipt.IReceiveReceiptWorkManager
import com.onesignal.session.internal.influence.IInfluenceManager
import com.onesignal.user.internal.subscriptions.ISubscriptionManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class NotificationListener(
    private val _applicationService: IApplicationService,
    private val _notificationLifecycleService: INotificationLifecycleService,
    private val _configModelStore: ConfigModelStore,
    private val _influenceManager: IInfluenceManager,
    private val _subscriptionManager: ISubscriptionManager,
    private val _deviceService: IDeviceService,
    private val _backend: INotificationBackendService,
    private val _receiveReceiptWorkManager: IReceiveReceiptWorkManager,
    private val _activityOpener: INotificationActivityOpener,
    private val _analyticsTracker: IAnalyticsTracker,
    private val _time: ITime,
) : IStartableService, INotificationLifecycleEventHandler {
    private val postedOpenedNotifIds = mutableSetOf<String>()

    override fun start() {
        _notificationLifecycleService.addInternalNotificationLifecycleEventHandler(this)
    }

    override suspend fun onNotificationReceived(notificationJob: NotificationGenerationJob) {
        _receiveReceiptWorkManager.enqueueReceiveReceipt(notificationJob.apiNotificationId)

        _influenceManager.onNotificationReceived(notificationJob.apiNotificationId)

        try {
            val jsonObject = JSONObject(notificationJob.jsonPayload.toString())
            jsonObject.put(NotificationConstants.BUNDLE_KEY_ANDROID_NOTIFICATION_ID, notificationJob.androidId)
            val openResult = NotificationHelper.generateNotificationOpenedResult(JSONUtils.wrapInJsonArray(jsonObject), _time)

            _analyticsTracker.trackReceivedEvent(
                openResult.notification.notificationId!!,
                NotificationHelper.getCampaignNameFromNotification(openResult.notification),
            )
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override suspend fun onNotificationOpened(
        activity: Activity,
        data: JSONArray,
    ) {
        val config = _configModelStore.model
        val appId: String = config.appId ?: ""
        val subscriptionId: String = _subscriptionManager.subscriptions.push.id
        val deviceType = _deviceService.deviceType

        for (i in 0 until data.length()) {
            val notificationId = NotificationFormatHelper.getOSNotificationIdFromJson(data[i] as JSONObject?) ?: continue

            if (postedOpenedNotifIds.contains(notificationId)) {
                continue
            }

            postedOpenedNotifIds.add(notificationId)

            try {
                _backend.updateNotificationAsOpened(
                    appId,
                    notificationId,
                    subscriptionId,
                    deviceType,
                )
            } catch (ex: BackendException) {
                Logging.error("Notification opened confirmation failed with statusCode: ${ex.statusCode} response: ${ex.response}")
            }
        }

        val openResult = NotificationHelper.generateNotificationOpenedResult(data, _time)
        _analyticsTracker.trackOpenedEvent(
            openResult.notification.notificationId!!,
            NotificationHelper.getCampaignNameFromNotification(openResult.notification),
        )

        // Handle the first element in the data array as the latest notification
        val latestNotificationId = getLatestNotificationId(data)

        if (shouldInitDirectSessionFromNotificationOpen(activity)) {
            // We want to set the app entry state to NOTIFICATION_CLICK when coming from background
            _applicationService.entryState = AppEntryAction.NOTIFICATION_CLICK
            if (latestNotificationId != null) {
                _influenceManager.onDirectInfluenceFromNotification(latestNotificationId)
            }
        }

        _activityOpener.openDestinationActivity(activity, data)
    }

    private fun shouldInitDirectSessionFromNotificationOpen(context: Activity): Boolean {
        if (_applicationService.isInForeground) {
            return false
        }

        try {
            return OSNotificationOpenAppSettings.getShouldOpenActivity(context)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return true
    }

    private fun getLatestNotificationId(data: JSONArray): String? {
        val latestNotification = if (data.length() > 0) data[0] as JSONObject else null
        return NotificationFormatHelper.getOSNotificationIdFromJson(latestNotification)
    }
}
