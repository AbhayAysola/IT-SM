package com.itsm.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A way of reaching a person. A new channel (Slack, push, ...) is a new class implementing this; [NotificationService]
 * picks the channels by priority and never needs to change.
 */
interface NotificationChannel {
    val key: ChannelKey

    /**
     * True when delivery belongs inside the business transaction (it writes to the database and must commit or roll back
     * with the change). False for external channels, which are only contacted after the commit, so a rolled-back change
     * notifies nobody and a failed email never undoes the change.
     */
    val transactional: Boolean

    fun deliver(notification: Notification)
}

/** The in-app inbox: the notification is stored, and the bell in the app shows it. */
@Component
class InAppChannel(private val store: NotificationStore) : NotificationChannel {
    override val key = ChannelKey.IN_APP
    override val transactional = true
    override fun deliver(notification: Notification) {
        store.insert(notification)
    }
}

/**
 * Simulated email (`PRODUCT.md`: email and SMS are simulated by severity): logs what would be sent. The address and the
 * text are not logged, only the recipient's id and the notification type, so the log holds no personal data.
 */
@Component
class SimulatedEmailChannel : NotificationChannel {
    private val log = LoggerFactory.getLogger(javaClass)
    override val key = ChannelKey.EMAIL
    override val transactional = false
    override fun deliver(notification: Notification) {
        log.info("EMAIL (simulated) to user #{}: {} about {}", notification.recipientId, notification.type, notification.incidentId ?: "-")
    }
}

/** Simulated SMS, for P1 incidents only (see [ChannelPolicy]). Same logging rules as [SimulatedEmailChannel]. */
@Component
class SimulatedSmsChannel : NotificationChannel {
    private val log = LoggerFactory.getLogger(javaClass)
    override val key = ChannelKey.SMS
    override val transactional = false
    override fun deliver(notification: Notification) {
        log.info("SMS (simulated) to user #{}: {} about {}", notification.recipientId, notification.type, notification.incidentId ?: "-")
    }
}
