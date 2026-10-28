package app.aaps.pump.apex.misc

import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewNotification
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Observable
import org.junit.jupiter.api.Test

/**
 * What [ApexNotifications.post] does when a card is put up again.
 *
 * The store behind it keeps the entry it already holds: adding an id that is already on screen
 * refreshes its timestamps and changes nothing else. So the only way to alter what a card SAYS is to
 * take it down first - and the only way to keep a ringing card ringing is to leave it alone, because
 * taking down a card that carries a sound stops the one alarm player. Those two pull in opposite
 * directions and [ApexNotifications.post] is where they are settled.
 *
 * `ApexAlarmCardsTest` covers the alarm-card side, where the wording never moves. This file covers
 * the other side: the cards that report a number.
 */
class ApexNotificationsTest {

    /** Any non-null raw resource id: only "has a sound" or "has none" matters here. */
    private val alarmSound = 4711

    private val bus = RecordingBus()

    private val notifications = ApexNotifications(bus)

    @Test
    fun aCardWhoseWordingMovedOnIsTakenDownSoTheNewOneGetsThrough() {
        notifications.post(ApexNotifications.RESERVOIR_LOW, "20 U left")
        bus.clear()

        notifications.post(ApexNotifications.RESERVOIR_LOW, "15 U left")

        // Without the take-down the store would keep "20 U left" on screen for as long as the card
        // stands, while the driver believed it had reported the new level.
        assertThat(bus.events).containsExactly(
            "dismiss ${ApexNotifications.RESERVOIR_LOW}",
            "post ${ApexNotifications.RESERVOIR_LOW} 15 U left"
        ).inOrder()
    }

    @Test
    fun theSameCardPutUpAgainIsNotTakenDown() {
        notifications.post(ApexNotifications.RESERVOIR_LOW, "20 U left")
        bus.clear()

        notifications.post(ApexNotifications.RESERVOIR_LOW, "20 U left")

        assertThat(bus.events).containsExactly("post ${ApexNotifications.RESERVOIR_LOW} 20 U left")
    }

    @Test
    fun goingQuietIsNotAReasonToTakeACardDown() {
        notifications.post(ApexNotifications.RESERVOIR_CRITICAL, "cartridge empty", soundRes = alarmSound)
        bus.clear()

        notifications.post(ApexNotifications.RESERVOIR_CRITICAL, "cartridge empty")

        // A standing card is put up again on every status frame and may not ring while it does. If
        // that silent repeat took the card down, the store would stop the siren with it.
        assertThat(bus.events).containsExactly("post ${ApexNotifications.RESERVOIR_CRITICAL} cartridge empty")
    }

    @Test
    fun aSoundTheStandingCardDoesNotHaveStillGetsThrough() {
        notifications.post(ApexNotifications.BOLUS_UNCONFIRMED, "check the pump")
        bus.clear()

        notifications.post(ApexNotifications.BOLUS_UNCONFIRMED, "check the pump", soundRes = alarmSound)

        // The store ignores an add for an id it already holds, sound and all, so a card that has to
        // start ringing has to come down first.
        assertThat(bus.events).containsExactly(
            "dismiss ${ApexNotifications.BOLUS_UNCONFIRMED}",
            "post ${ApexNotifications.BOLUS_UNCONFIRMED} check the pump"
        ).inOrder()
    }

    @Test
    fun aRingingCardIsNotTakenDownWhenOnlyItsLevelMoves() {
        notifications.post(ApexNotifications.PUMP_LOCKED, "delivery stopped",
                           level = Notification.URGENT, soundRes = alarmSound)
        bus.clear()

        // The suspended-pump card recomputes its level from "is a bolus running" on every status
        // frame, so this repeat is the ordinary case, not a corner one.
        notifications.post(ApexNotifications.PUMP_LOCKED, "delivery stopped", level = Notification.NORMAL)

        // Taking it down would stop the one alarm player: the siren would die one frame after it
        // started, on the card that says insulin has stopped.
        assertThat(bus.events).containsExactly("post ${ApexNotifications.PUMP_LOCKED} delivery stopped")
    }

    @Test
    fun takingDownARingingCardStartsTheSirenAgainForOneStillStanding() {
        val first = notifications.postAlarmCard("occlusion", Notification.URGENT, alarmSound, into = null)
        val second = notifications.postAlarmCard("cartridge empty", Notification.URGENT, alarmSound, into = null)
        bus.clear()

        notifications.dismiss(first)

        // The store stops the single alarm player for ANY card it removes that carries a sound, so an
        // alarm the user cleared on the pump would take the siren of one that still stands with it.
        // The survivor has to come down and go back up, because the store ignores an add for an id it
        // already holds.
        assertThat(bus.events).containsExactly(
            "dismiss ${first.id}",
            "dismiss ${second.id}",
            "post ${second.id} cartridge empty"
        ).inOrder()
    }

    @Test
    fun aCardDoesNotGoAwayOnItsOwn() {
        notifications.post(ApexNotifications.PUMP_LINK_LOST, "no answer from the pump")

        // validTo = 0 means "stands until the driver takes it down". Anything else would let the card
        // expire while the pump is still unreachable.
        assertThat(bus.posted.single().validTo).isEqualTo(0)
    }

    @Test
    fun everyStandingAlarmGetsItsOwnCardAndADismissedIdComesBack() {
        val first = notifications.postAlarmCard("occlusion", Notification.URGENT, null, into = null)
        val second = notifications.postAlarmCard("communication fault", Notification.URGENT, null, into = null)

        // One id per standing alarm is the whole point: on this version the id IS the card, so a
        // shared id means the second alarm replaces the first and its text and siren go with it.
        assertThat(second.id).isNotEqualTo(first.id)

        // The pool is ten ids wide and a session sees far more than ten alarms come and go, so a
        // dismissed card has to hand its id back. A freed id goes to the BACK of the queue, so the
        // next alarm does not land on a card the store may still be taking down - which is why this
        // checks that the pool never runs dry, not that the very same id comes straight back.
        repeat(30) {
            val handle = notifications.postAlarmCard("alarm $it", Notification.URGENT, null, into = null)
            assertThat(handle.id).isNotEqualTo(ApexNotifications.ALARM_CARD_OVERFLOW)
            notifications.dismiss(handle)
        }
    }

    private class RecordingBus : RxBus {

        val events = mutableListOf<String>()
        val posted = mutableListOf<Notification>()

        override fun send(event: Event) {
            when (event) {
                is EventNewNotification     -> {
                    posted.add(event.notification)
                    events.add("post ${event.notification.id} ${event.notification.text}")
                }

                is EventDismissNotification -> events.add("dismiss ${event.id}")
                else                        -> {}
            }
        }

        /** Nothing in this test reads the bus. */
        override fun <T : Any> toObservable(eventType: Class<T>): Observable<T> = Observable.empty()

        fun clear() {
            events.clear()
            posted.clear()
        }
    }
}
