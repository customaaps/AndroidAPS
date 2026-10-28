package app.aaps.pump.apex

import app.aaps.pump.apex.connectivity.commands.pump.PumpAlarm
import app.aaps.pump.apex.misc.ApexNotifications

/**
 * Keeps one notification card per standing pump alarm.
 *
 * The pump reports up to ten alarm slots at once. While all of them went to one notification id the
 * second alarm replaced the first - its text was lost and the first alarm's siren stopped with it,
 * because the replacement carried no sound. Every standing alarm now owns its card and its handle,
 * so nothing replaces anything.
 *
 * Not thread safe: [sync] must be called under the caller's lock - every status frame is processed
 * on its own worker thread (see `ApexService.processObject`).
 *
 * [post] takes the card's current handle as its third argument, or null for an alarm that has no
 * card yet. A card is a notification id here (see [ApexNotifications]), so putting a card back has
 * to land on that card's own id; handing back a fresh one would leave the old card standing and add
 * a second one saying the same thing.
 */
internal class ApexAlarmCards(
    private val post: (PumpAlarm, Boolean, ApexNotifications.Handle?) -> ApexNotifications.Handle,
    private val dismiss: (ApexNotifications.Handle) -> Unit,
    private val isOnScreen: (ApexNotifications.Handle) -> Boolean,
) {

    private class Card(val alarm: PumpAlarm, var handle: ApexNotifications.Handle)

    private val cards = mutableListOf<Card>()

    /**
     * Make the cards match [current]. Only an alarm in [newlyArrived] is allowed to ring.
     *
     * A multiset match, like `ApexService.newAlarms`: the pump can report one code in two slots, and
     * those are two alarms with two cards.
     */
    fun sync(current: List<PumpAlarm>, newlyArrived: List<PumpAlarm>) {
        val withoutCard = current.toMutableList()
        // The alarm behind this card is gone: take down exactly this card, nothing else.
        cards.removeAll { card ->
            if (withoutCard.remove(card.alarm)) false
            else {
                dismiss(card.handle)
                true
            }
        }
        // The card was taken away while the alarm still stands: swiped from the shade, or dismissed
        // in the app. This version cannot be asked whether a card is still there, so the card is put
        // back on every frame - which refreshes it in place and silently while it stands, and brings
        // it back once it is gone (see `ApexNotifications.isOnScreen`). Never with sound, or a code
        // the pump only clears on a physical acknowledge could not be silenced.
        for (card in cards) if (!isOnScreen(card.handle)) card.handle = post(card.alarm, false, card.handle)
        // Alarms with no card at all. Sound only for what the pump reports as new.
        val mayRing = newlyArrived.toMutableList()
        for (alarm in withoutCard) cards.add(Card(alarm, post(alarm, mayRing.remove(alarm), null)))
    }
}
