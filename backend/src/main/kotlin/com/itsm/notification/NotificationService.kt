package com.itsm.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Delivers notifications through the channels each one asks for. Transactional channels (the in-app inbox) run now, in
 * the caller's transaction; external ones (email, SMS) run only after it commits, and a failure there is logged, never
 * propagated: a notification must not undo the change it reports.
 */
@Service
class NotificationService(channels: List<NotificationChannel>) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byKey: Map<ChannelKey, NotificationChannel> = channels.associateBy { it.key }

    init {
        require(ChannelKey.entries.all { it in byKey }) { "every channel key needs exactly one NotificationChannel bean" }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun send(notifications: Collection<Notification>) {
        val later = mutableListOf<Pair<NotificationChannel, Notification>>()
        for (n in notifications) {
            for (key in n.channels.sortedBy { it.ordinal }) {
                val channel = byKey.getValue(key)
                if (channel.transactional) channel.deliver(n) else later += channel to n
            }
        }
        if (later.isNotEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    for ((channel, n) in later) {
                        try {
                            channel.deliver(n)
                        } catch (e: Exception) {
                            log.warn("{} delivery of {} to user #{} failed", channel.key, n.type, n.recipientId, e)
                        }
                    }
                }
            })
        }
    }
}
