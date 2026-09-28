package glass.kagerou.piru.notifications

import android.content.Context
import glass.kagerou.piru.model.doseFormatted
import java.util.UUID

/**
 * The low-stock and out-of-stock alerts for a tracked supply.
 *
 * Ported from `DoseNotificationManager.inventoryLowStock(substance:remaining:unit:isOut:itemID:)`
 * and `cancelInventoryLowStock(itemID:)`.
 *
 * ## Why this is not a scheduler
 * Every other family in this package works out *when* something should happen.
 * This one is told that it already did: a stock level crossed its threshold, and
 * the alert is the announcement. It is delivered immediately rather than armed
 * for later, and there is nothing to reconcile — which is also why it is not part
 * of any of the three schedulers. It hangs off the inventory service, which is
 * where the crossing is detected.
 *
 * ## De-duplication is the caller's
 * iOS keeps a `lowStockNotified` flag on the item so the alert fires once per
 * shortage rather than once per frame, and clears it when a restock takes the
 * quantity back over the threshold. That flag lives on
 * `InventoryItemEntity.lowStockNotified` and is owned by the inventory service;
 * nothing here decides whether to send, only how it reads once something has.
 */
object InventoryNotifier {

    /** The one thread every stock alert joins, so the shade groups them as one kind of message. */
    const val THREAD = "piru.notif.thread.inventory"

    /**
     * Deliver the alert now.
     *
     * Nothing is scheduled and nothing is deferred. A stock level crossed while
     * the user was in the app, so the banner is suppressed until they next leave
     * it and surfaces then — which is the intended "you have run low" nudge for a
     * threshold crossed by an in-app log, rather than a notification fighting the
     * screen the user is already looking at.
     */
    fun lowStock(
        context: Context,
        substance: String,
        remaining: Double,
        unit: String,
        isOut: Boolean,
        itemId: UUID,
    ) {
        if (!NotificationPreferencesStore.allows(context, NotificationType.INVENTORY)) return

        PiruNotifications.post(
            context = context,
            payload = PlannedNotification(
                identifier = NotificationType.INVENTORY.identifier(itemId.toString()),
                channelId = NotificationType.INVENTORY.channelId,
                title = if (isOut) "Out of $substance" else "Running low on $substance",
                body = if (isOut) {
                    "You're out of $substance. Restock when you can."
                } else {
                    "${doseFormatted(remaining)} $unit of $substance left."
                },
                threadKey = THREAD,
                deepLink = "$SCHEME://inventory/$itemId",
            ),
        )
    }

    /**
     * Clear any alert for an item, pending or already delivered.
     *
     * Both, because a "running low" banner sitting on the lock screen for a
     * supply the user has stopped tracking is worse than no alert at all: it is a
     * claim about something they no longer own. Called when tracking is turned
     * off, and from the restock path when the quantity goes back up.
     */
    fun cancel(context: Context, itemId: UUID) {
        PiruNotifications.cancel(
            context,
            // The legacy identifier too: a pending alert written by a build before
            // the `piru.notif.` grammar shipped carries the old `inventoryLowStock_`
            // prefix and would otherwise never be cancelled.
            listOf(
                NotificationType.INVENTORY.identifier(itemId.toString()),
                NotificationType.INVENTORY_LEGACY_PREFIX + itemId,
            ),
        )
    }

    private const val SCHEME = DoseNotificationScheduler.DEEP_LINK_SCHEME
}
