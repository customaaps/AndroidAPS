package app.aaps.pump.apex

import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.pump.apex.connectivity.commands.pump.Alarm
import app.aaps.pump.apex.connectivity.commands.pump.PumpAlarm
import app.aaps.pump.apex.misc.ApexNotifications
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Observable
import org.junit.jupiter.api.Test

/**
 * One notification card per standing pump alarm, and what the next alarm may not take from it.
 *
 * The case this file exists for: every Apex alarm used to go to the one shared `PUMP_ERROR` card,
 * where the card is the id. The second alarm therefore REPLACED the first - its text left the screen
 * and, because the replacement carried no sound of its own, the first alarm's siren was stopped with
 * it. An occlusion silenced by a communication fault arriving a minute later is the real case; the
 * pump reports up to ten alarm slots at once.
 *
 * [ApexNotifications] is the real thing here, because on this version a card IS a notification id:
 * the id pool it hands alarm cards out of is what lets two alarms stand at once, and it is the piece
 * that has to be exercised. [Screen] below is a small copy of the two rules of `NotificationStore`
 * that decide the rest: posting an id the store already holds only refreshes it - no second card, no
 * Android alert, no sound - and taking down a card that carries a sound stops the one alarm player.
 * Point the pool at a single fixed id and the first, second and same-code tests go red - that is the
 * defect, not the new code.
 *
 * `ApexNotifications.isOnScreen` cannot answer on this version, so every standing card is put back
 * on every status frame. That silent re-post must never take the card down: removing a card that
 * carries a sound stops the one alarm player, so a ringing alarm would fall silent on the very next
 * frame - the siren would be gone long before the alarm it belongs to. `ApexNotifications.post`
 * therefore does not treat a missing sound as a change worth replacing a card for. Make it compare
 * the sound in both directions and `aSilentAlarmArrivingBehindARingingOne...` goes red: that is the
 * defect, not the rule. The tests run one settling frame first, so what they then measure is the
 * arriving or clearing alarm's doing and not the re-post's.
 */
class ApexAlarmCardsTest {

    /** Any non-null raw resource id: only "has a sound" or "has none" matters here. */
    private val alarmSound = 4711

    private val screen = Screen()

    private val notifications = ApexNotifications(screen)

    private val cards = ApexAlarmCards(
        // The same choice ApexService.postAlarm makes, kept in one place so the test can undo it.
        post = { alarm, sound, into ->
            notifications.postAlarmCard(
                text = "${alarm.type.name} ${alarm.code}",
                level = if (ApexDriverLogic.isUrgent(alarm.type)) Notification.URGENT else Notification.NORMAL,
                soundRes = if (sound && ApexDriverLogic.ringsFor(alarm.type)) alarmSound else null,
                into = into,
            )
        },
        dismiss = { handle -> notifications.dismiss(handle) },
        isOnScreen = { handle -> notifications.isOnScreen(handle) },
    )

    private val occlusion = PumpAlarm(Alarm.NoDelivery)

    /** Urgent, but on the closed list of codes that may ring it is absent - so it carries no sound. */
    private val commsFault = PumpAlarm(Alarm.CommunicationError)

    private val lowReservoir = PumpAlarm(Alarm.LowReservoir)

    @Test
    fun aSilentAlarmArrivingBehindARingingOneTakesNeitherItsCardNorItsSiren() {
        cards.sync(listOf(occlusion), listOf(occlusion))
        assertThat(screen.sirenStarts).isEqualTo(1)
        // One frame with nothing new, so whatever the silent re-post does has already happened before
        // the second alarm arrives and cannot be blamed on it. The siren has to come through it: the
        // alarm still stands, and the re-post carries no sound only because nothing new may ring.
        cards.sync(listOf(occlusion), emptyList())
        assertThat(screen.sirenPlaying).isTrue()
        val standing = screen.cards().single()
        val alertsBefore = screen.alerts

        cards.sync(listOf(occlusion, commsFault), listOf(commsFault))

        assertThat(screen.cards()).hasSize(2)
        assertThat(screen.cards().map { it.text }).containsNoDuplicates()
        // Same card object: the first alarm's card was neither replaced nor taken down and put back.
        assertThat(screen.cards()).contains(standing)
        // Only the new alarm raised anything, and it silenced nothing: the occlusion is still ringing.
        assertThat(screen.alerts).isEqualTo(alertsBefore + 1)
        assertThat(screen.sirenPlaying).isTrue()
    }

    @Test
    fun twoAlarmsAtOnceGetTwoCards() {
        val both = listOf(occlusion, commsFault)

        cards.sync(both, both)

        assertThat(screen.cards()).hasSize(2)
        assertThat(screen.cards().map { it.text }).containsNoDuplicates()
        assertThat(screen.cards().map { it.id }).containsNoDuplicates()
    }

    @Test
    fun anAlarmThatClearsTakesDownOnlyItsOwnCard() {
        val both = listOf(occlusion, lowReservoir)
        cards.sync(both, both)
        // The settling frame again: after it, anything that happens is the clearing alarm's doing.
        cards.sync(both, emptyList())
        val standing = screen.cards().single { it.text.startsWith(Alarm.NoDelivery.name) }
        val alertsBefore = screen.alerts
        val sirenBefore = screen.sirenPlaying

        cards.sync(listOf(occlusion), emptyList())

        assertThat(screen.cards()).hasSize(1)
        // The card that stands is the occlusion's own card, untouched, and still the urgent one.
        assertThat(screen.cards().single()).isSameInstanceAs(standing)
        assertThat(screen.cards().single().level).isEqualTo(Notification.URGENT)
        assertThat(screen.alerts).isEqualTo(alertsBefore)
        assertThat(screen.sirenPlaying).isEqualTo(sirenBefore)
    }

    @Test
    fun theLastAlarmClearingTakesDownTheLastCard() {
        cards.sync(listOf(occlusion), listOf(occlusion))

        cards.sync(emptyList(), emptyList())

        assertThat(screen.cards()).isEmpty()
        assertThat(screen.sirenPlaying).isFalse()
    }

    @Test
    fun aCardTheUserClearedComesBackWithoutSound() {
        cards.sync(listOf(occlusion), listOf(occlusion))
        // Swiped from the shade, dismissed in the app, or deleted by muteAllAlarms(). Only the store
        // forgets it; the driver is not told, which is why the card is put back on the next frame.
        screen.userCleared(screen.cards().single().id)

        cards.sync(listOf(occlusion), emptyList())

        assertThat(screen.cards()).hasSize(1)
        assertThat(screen.cards().single().soundRes).isNull()
        assertThat(screen.sirenPlaying).isFalse()
        // The ramp the user just stopped was not started again by the card coming back.
        assertThat(screen.sirenStarts).isEqualTo(1)
    }

    @Test
    fun aStandingAlarmIsNotAlertedAgainOnEveryFrame() {
        cards.sync(listOf(occlusion), listOf(occlusion))
        // The card is put back on every frame by design - this version cannot ask whether it is
        // still on screen. What must not repeat is the alert: the store keeps the card it already
        // holds, so putting the same card back raises nothing.
        cards.sync(listOf(occlusion), emptyList())
        val settled = screen.alerts

        cards.sync(listOf(occlusion), emptyList())
        cards.sync(listOf(occlusion), emptyList())
        cards.sync(listOf(occlusion), emptyList())

        // A fresh alert per status frame is how people learn to mute the channel, and the next real
        // alarm goes with it.
        assertThat(screen.alerts).isEqualTo(settled)
        assertThat(screen.cards()).hasSize(1)
        assertThat(screen.sirenStarts).isEqualTo(1)
    }

    @Test
    fun theSameCodeInTwoSlotsGetsTwoCards() {
        // The pump can report one code in two slots, and those are two alarms.
        val twice = listOf(PumpAlarm.of(0x300), PumpAlarm.of(0x300))
        cards.sync(twice, twice)
        assertThat(screen.cards()).hasSize(2)
        assertThat(screen.cards().map { it.id }).containsNoDuplicates()

        cards.sync(listOf(PumpAlarm.of(0x300)), emptyList())

        assertThat(screen.cards()).hasSize(1)
    }

    @Test
    fun anAlarmStandingSinceBeforeTheRestartDoesNotRing() {
        // First frame after the service was recreated: the alarm is not new to the pump, so it gets
        // its card back but must not start the ramp again.
        cards.sync(listOf(occlusion), emptyList())

        assertThat(screen.cards()).hasSize(1)
        assertThat(screen.cards().single().soundRes).isNull()
        assertThat(screen.sirenStarts).isEqualTo(0)
        assertThat(screen.sirenPlaying).isFalse()
    }

    @Test
    fun everyStandingAlarmGetsACardIdOfItsOwn() {
        // dev4 kept this scheme alive with NotificationId.allowMultiple. Here a card is an id, so
        // the same guarantee is the pool: one free id per alarm slot the pump can report. Take the
        // pool away and the second card replaces the first, and the whole class is pointless.
        val slots = ApexNotifications.ALARM_CARD_SLOTS
        assertThat(slots).isEqualTo(10)
        val many = (0 until slots).map { PumpAlarm.of(0x300 + it) }

        cards.sync(many, many)

        assertThat(screen.cards()).hasSize(slots)
        val ids = screen.cards().map { it.id }
        assertThat(ids).containsNoDuplicates()
        // Every card sits inside the driver's own pool, so an alarm can never land on the id of
        // another Apex card and take that one down instead.
        assertThat(ids.all { it in ApexNotifications.ALARM_CARD_FIRST until ApexNotifications.ALARM_CARD_FIRST + slots }).isTrue()
        assertThat(ids).containsNoneIn(
            listOf(
                ApexNotifications.ALARM_CARD_OVERFLOW,
                ApexNotifications.RESERVOIR_LOW,
                ApexNotifications.RESERVOIR_CRITICAL,
                ApexNotifications.PERMISSION_MISSING,
                ApexNotifications.PUMP_LINK_LOST,
                ApexNotifications.BLUETOOTH_OFF,
                ApexNotifications.BOLUS_UNCONFIRMED,
                ApexNotifications.PASSWORD_REJECTED,
                ApexNotifications.PUMP_LOCKED,
                ApexNotifications.INVALID_OBJECT,
            )
        )
    }

    /**
     * The two rules of `NotificationStore` this scheme depends on, and nothing else.
     *
     * `add`: an id the store already holds only gets its dates refreshed - the call returns before it
     * can make a second card, raise an Android alert or start a sound. `remove`: a card that carries a
     * sound stops the one alarm player as it goes.
     *
     * It stands in for the real store by being the bus [ApexNotifications] writes to, which is how the
     * driver reaches the store in the app.
     */
    private class Screen : RxBus {

        class Card(val id: Int, val text: String, val level: Int, val soundRes: Int?)

        private val onScreen = mutableListOf<Card>()

        /** Cards the store really accepted. Each one is a fresh Android alert. */
        var alerts = 0
            private set

        /** How often the ramping alarm was started, to catch a re-post that rings again. */
        var sirenStarts = 0
            private set

        /** The one alarm player: started by a card that carries a sound, stopped when it goes. */
        var sirenPlaying = false
            private set

        override fun send(event: Event) {
            when (event) {
                is EventNewNotification     -> add(event.notification)
                is EventDismissNotification -> remove(event.id)
                else                        -> {}
            }
        }

        /** Nothing in this test reads the bus. */
        override fun <T : Any> toObservable(eventType: Class<T>): Observable<T> = Observable.empty()

        private fun add(notification: Notification) {
            if (onScreen.any { it.id == notification.id }) return
            onScreen.add(Card(notification.id, notification.text, notification.level, notification.soundId))
            alerts++
            if (notification.soundId != null) {
                sirenStarts++
                sirenPlaying = true
            }
        }

        private fun remove(id: Int) {
            val card = onScreen.firstOrNull { it.id == id } ?: return
            onScreen.remove(card)
            if (card.soundRes != null) sirenPlaying = false
        }

        fun cards(): List<Card> = onScreen.toList()

        /** What a person swiping the card away does: the store forgets it, the driver is not told. */
        fun userCleared(id: Int) = remove(id)
    }
}
