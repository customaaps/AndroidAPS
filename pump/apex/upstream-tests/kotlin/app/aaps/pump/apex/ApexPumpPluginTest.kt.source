package app.aaps.pump.apex

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.pump.apex.misc.ApexNotifications
import app.aaps.pump.apex.misc.BatteryType
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import app.aaps.core.ui.R as CoreUiR

/**
 * What the plugin hands to AAPS-core out of the pump model.
 *
 * The battery is the case worth pinning. `ApexPump.batteryLevel` answers "no status frame yet" with
 * 0 %, which is also what the pump reports when the cell really is Dead, and this plugin used to
 * publish that. Core has no way to tell the two apart, `TriggerPumpBatteryLevel` reads the published
 * value straight off the plugin, and [ApexPumpPlugin.refreshReactiveState] runs from `onStart()` -
 * so a "battery below N" automation saw 0 % on every app start.
 */
class ApexPumpPluginTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var context: Context
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var fabricPrivacy: FabricPrivacy
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var config: Config
    @Mock lateinit var constraintsChecker: ConstraintsChecker
    @Mock lateinit var apexNotifications: ApexNotifications

    private lateinit var pump: ApexPump
    private lateinit var sut: ApexPumpPlugin

    @BeforeEach
    fun setUp() {
        // Alkaline (1.20-1.47 V) so only these two keys need stubbing - see CommandsTest.
        whenever(preferences.get(ApexBooleanKey.CalculateBatteryPercentage)).thenReturn(true)
        whenever(preferences.get(ApexStringKey.CalcBatteryType)).thenReturn(BatteryType.Alkaline.name)
        pump = ApexPump(preferences, aapsLogger)
        sut = ApexPumpPlugin(
            aapsLogger, rh, commandQueue, context, preferences, rxBus, aapsSchedulers,
            fabricPrivacy, dateUtil, pump, config, constraintsChecker, apexNotifications
        )
    }

    @Test
    fun batteryIsPublishedAsUnknownUntilTheFirstStatusFrame() {
        sut.refreshReactiveState()

        assertThat(sut.batteryLevel).isNull()
    }

    @Test
    fun batteryIsPublishedOnceThePumpHasReportedOne() {
        pump.updateFromV2(ApexTestFrames.statusV2(centiVolts = 140))

        sut.refreshReactiveState()

        assertThat(sut.batteryLevel).isEqualTo(74) // 1.40 V over 1.20-1.47 V
    }

    /** Every non-exportable key, with a value that is recognisable in a failure message. */
    private val driverState = mapOf(
        ApexStringKey.LastConnectedSerialNumber to "12345678",
        ApexStringKey.BluetoothAddress to "AA:BB:CC:DD:EE:FF",
        ApexStringKey.PumpIdFormatCutover to "1756800000000",
        ApexStringKey.PendingBolusSync to "1756800000000|1756800000000|40|NORMAL",
    )

    /**
     * The list above has to be the whole of it, or the tests below would quietly cover less than they
     * claim: an unlisted key is stubbed to Mockito's default and still round-trips, so nothing fails.
     * Adding a non-exportable key therefore fails HERE, where the message says what to do.
     */
    @Test
    fun everyNonExportableKeyIsCoveredByTheseTests() {
        assertThat(driverState.keys).containsExactlyElementsIn(ApexStringKey.entries.filter { !it.exportable })
    }

    private fun stubDriverState() = driverState.forEach { (key, value) -> whenever(preferences.get(key)).thenReturn(value) }

    /**
     * The point of the pair of hooks: an import runs `sp.clear()` between them, and `exportable = false`
     * does not survive that on its own. Losing PendingBolusSync is the one that costs insulin - the
     * bolus is then stored a second time under its pumpId and counted twice in IOB.
     */
    @Test
    fun driverStateIsPutBackAfterASettingsImport() {
        stubDriverState()

        sut.beforeImport()
        sut.afterImport()

        driverState.forEach { (key, value) -> verify(preferences).put(key, value) }
    }

    /** An empty value is written back as empty, not left at whatever the import put there. */
    @Test
    fun anEmptyDriverStateValueIsPutBackAsEmpty() {
        stubDriverState()
        whenever(preferences.get(ApexStringKey.PendingBolusSync)).thenReturn("")

        sut.beforeImport()
        sut.afterImport()

        verify(preferences).put(ApexStringKey.PendingBolusSync, "")
    }

    /** Nothing was saved, so there is nothing to put back - and nothing to overwrite either. */
    @Test
    fun afterImportWithoutBeforeImportWritesNothing() {
        sut.afterImport()

        // anyOrNull, not any: an unstubbed get answers null, so a restore that should not have happened
        // would write put(key, null) - and any() refuses nulls, which would let it through unseen.
        driverState.keys.forEach { key -> verify(preferences, never()).put(eq(key), anyOrNull<String>()) }
    }

    /** Exportable keys travel in the file, so the hooks must not write them back over the import. */
    @Test
    fun exportableKeysAreNotTouchedByTheHooks() {
        stubDriverState()

        sut.beforeImport()
        sut.afterImport()

        // anyOrNull for the same reason as above: neither key is stubbed here, so a hook that wrongly
        // wrote them would write nulls, which any() cannot see.
        verify(preferences, never()).put(eq(ApexStringKey.SerialNumber), anyOrNull<String>())
        verify(preferences, never()).put(eq(ApexStringKey.BolusSpeed), anyOrNull<String>())
    }

    /**
     * The handle production really hands out for this card.
     *
     * This version of AAPS has no notification handles: the id IS the card. So the handle of a
     * hand-given bolus's card is always `Handle(BOLUS_UNCONFIRMED)`, a replacement card carries the
     * same handle as the one it replaces - which is why the order of dismiss and post matters - and
     * an SMB's card, being a different id, carries a different handle.
     */
    private val cardOnScreen = ApexNotifications.Handle(ApexNotifications.BOLUS_UNCONFIRMED)

    /** The handle a post hands back; without this the mock answers null and a null assertion proves nothing. */
    private fun stubPostReturns(handle: ApexNotifications.Handle) {
        whenever(
            apexNotifications.post(any<Int>(), anyOrNull<String>(), any<Int>(), anyOrNull<Int>())
        ).thenReturn(handle)
    }

    /**
     * Stubbed per argument, not per wildcard, so the assertions below are about THESE numbers.
     * A wildcard stub would answer the same text whatever was formatted, and the tests would still
     * pass with the requested and confirmed amounts swapped, or with either one missing.
     */
    private fun stubUnconfirmedCard(requested: Double, confirmed: Double) {
        // Each amount formats to a text derived from the amount itself, so the two stubs agree when the
        // two amounts are equal - which is the same invocation, and so can only have one answer. Texts
        // that carried their role instead ("asked ..." / "got ...") disagreed there, the composed stub
        // below never matched, rh.gs answered null, and assertions about that text quietly stopped
        // testing anything.
        whenever(rh.gs(CoreUiR.string.format_insulin_units, requested)).thenReturn("$requested U")
        whenever(rh.gs(CoreUiR.string.format_insulin_units, confirmed)).thenReturn("$confirmed U")
        whenever(rh.gs(R.string.bolus_unconfirmed, "$requested U", "$confirmed U"))
            .thenReturn("asked $requested U, got $confirmed U")
    }

    /** No card of any kind went up. */
    private fun verifyNoCardPosted() {
        // anyOrNull for the text, not any: any() refuses nulls, and an unstubbed rh.gs hands back
        // null - so a post that DID happen would slip past the matcher and the guard would pass on
        // exactly the regression it is here to catch. Same for the sound, which is null on an SMB.
        verify(apexNotifications, never()).post(any<Int>(), anyOrNull<String>(), any<Int>(), anyOrNull<Int>())
    }

    /** How many cards went up, whatever they said. */
    private fun verifyCardsPosted(count: Int) {
        verify(apexNotifications, times(count)).post(any<Int>(), anyOrNull<String>(), any<Int>(), anyOrNull<Int>())
    }

    /** A hand-given bolus: somebody is waiting for that insulin, so it has to be heard. */
    @Test
    fun anUnconfirmedManualBolusIsAnnouncedAudibly() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.75)

        sut.warnBolusUnconfirmed(2.0, 0.75, BS.Type.NORMAL)

        verify(apexNotifications).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED), eq("asked 2.0 U, got 0.75 U"),
            eq(Notification.URGENT), eq(CoreUiR.raw.boluserror)
        )
    }

    /** An SMB is small and the loop corrects itself, so it is shown without waking anybody. */
    @Test
    fun anUnconfirmedSmbIsAnnouncedSilently() {
        stubUnconfirmedCard(requested = 0.2, confirmed = 0.0)

        sut.warnBolusUnconfirmed(0.2, 0.0, BS.Type.SMB)

        verify(apexNotifications).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED_SMB), eq("asked 0.2 U, got 0.0 U"),
            eq(Notification.NORMAL), isNull<Int>()
        )
    }

    /**
     * On this version a notification id IS the card, so the two kinds are given an id each. Share one
     * and this silent line about a fraction of a unit replaces an URGENT audible card about insulin
     * somebody is standing there waiting for.
     */
    @Test
    fun anUnconfirmedSmbDoesNotTakeDownAStandingManualWarning() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.75)
        sut.warnBolusUnconfirmed(2.0, 0.75, BS.Type.NORMAL)

        stubUnconfirmedCard(requested = 0.2, confirmed = 0.0)
        sut.warnBolusUnconfirmed(0.2, 0.0, BS.Type.SMB)

        // Two cards on two ids: the loud one was never touched, and the SMB was not dropped for it
        // either - point both posts at one id and the count still passes, so the ids are checked too.
        verifyCardsPosted(2)
        verify(apexNotifications).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED), eq("asked 2.0 U, got 0.75 U"),
            eq(Notification.URGENT), eq(CoreUiR.raw.boluserror)
        )
        verify(apexNotifications).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED_SMB), eq("asked 0.2 U, got 0.0 U"),
            eq(Notification.NORMAL), isNull<Int>()
        )
    }

    /** A run of them on a bad link all lands on the one SMB id, so the screen cannot fill up. */
    @Test
    fun anUnconfirmedSmbReplacesAStandingSmbWarning() {
        stubUnconfirmedCard(requested = 0.2, confirmed = 0.0)

        sut.warnBolusUnconfirmed(0.2, 0.0, BS.Type.SMB)
        sut.warnBolusUnconfirmed(0.2, 0.0, BS.Type.SMB)

        verify(apexNotifications, times(2)).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED_SMB), eq("asked 0.2 U, got 0.0 U"),
            eq(Notification.NORMAL), isNull<Int>()
        )
    }

    /**
     * Settling the hand-given bolus is about that bolus alone. The SMB card is a different id, so
     * nothing shared can be taken down with it - which is the whole reason the ids were split.
     */
    @Test
    fun takingTheManualCardDownLeavesTheSmbCardAlone() {
        stubUnconfirmedCard(requested = 0.2, confirmed = 0.0)
        sut.warnBolusUnconfirmed(0.2, 0.0, BS.Type.SMB)
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.75)
        stubPostReturns(cardOnScreen)
        sut.warnBolusUnconfirmed(2.0, 0.75, BS.Type.NORMAL)

        // The pump then proved the hand-given bolus whole, which is what takes its card down.
        stubUnconfirmedCard(requested = 2.0, confirmed = 2.0)
        sut.refreshUnconfirmedWarning(
            inProgressBolus(requested = 2.0, delivered = 2.0).apply {
                unconfirmedWarning = cardOnScreen
                unconfirmedWarningDose = 0.75
            }
        )

        verify(apexNotifications).dismiss(cardOnScreen)
        verify(apexNotifications, never())
            .dismiss(ApexNotifications.Handle(ApexNotifications.BOLUS_UNCONFIRMED_SMB))
    }

    /**
     * The handle is what lets the driver take down THIS card later. Dismissing by a number the caller
     * made up instead would take down whatever card that number names.
     */
    @Test
    fun theCardHandleIsHandedBackSoItsOwnBolusCanTakeItDown() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.75)
        val handle = ApexNotifications.Handle(77)
        stubPostReturns(handle)

        assertThat(sut.warnBolusUnconfirmed(2.0, 0.75, BS.Type.NORMAL)).isEqualTo(handle)
    }

    /** The SMB handle has to name the SMB card, or settling that SMB would take the loud card down. */
    @Test
    fun theSmbCardHandleNamesTheSmbCard() {
        stubUnconfirmedCard(requested = 0.2, confirmed = 0.0)
        // Answer with the id the plugin really asked for, so the assertion is about that id and not
        // about a handle this test made up and then found again.
        whenever(apexNotifications.post(any<Int>(), anyOrNull<String>(), any<Int>(), anyOrNull<Int>()))
            .thenAnswer { ApexNotifications.Handle(it.getArgument<Int>(0)) }

        assertThat(sut.warnBolusUnconfirmed(0.2, 0.0, BS.Type.SMB))
            .isEqualTo(ApexNotifications.Handle(ApexNotifications.BOLUS_UNCONFIRMED_SMB))
    }

    private fun inProgressBolus(
        requested: Double,
        delivered: Double,
        cancelled: Boolean = false,
        failed: Boolean = false,
        type: BS.Type = BS.Type.NORMAL,
    ) = ApexPump.InProgressBolus(
        requestedDose = requested,
        currentDose = delivered,
        temporaryId = 1,
        cancelled = cancelled,
        detailedBolusInfo = DetailedBolusInfo().apply { insulin = requested; bolusType = type },
        treatment = ApexPump.Treatment(insulin = requested, carbs = 0, isSMB = type == BS.Type.SMB, id = null),
        failed = failed,
    )

    /**
     * The whole point of the single owner: the thread that proves the bolus whole takes the card down,
     * whichever thread put it there.
     */
    @Test
    fun aBolusThePumpConfirmsInFullTakesItsCardDown() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 2.0)
        val card = cardOnScreen
        val bolus = inProgressBolus(requested = 2.0, delivered = 2.0).apply {
            unconfirmedWarning = card
            unconfirmedWarningDose = 0.5
        }

        sut.refreshUnconfirmedWarning(bolus)

        verify(apexNotifications).dismiss(card)
        // Taken down, not swapped: same guard as the abandoned-bolus twin. Without it the assertion
        // below would pass on a re-post too, because an unstubbed post hands back null.
        verifyNoCardPosted()
        assertThat(bolus.unconfirmedWarning).isNull()
    }

    /**
     * The pump can settle the doubt the other way. The card is then right and must not be taken down -
     * but it names the figure the wait ended on, which the pump has just superseded, so it is replaced
     * rather than left saying a number that is no longer true.
     */
    @Test
    fun aBolusThePumpConfirmsAsShortGetsTheCardWithThePumpsOwnFigure() {
        // The SAME handle for both, because that is what production produces: the id IS the card, so
        // every card of it answers Handle(BOLUS_UNCONFIRMED). That is also why the order below is
        // pinned - a post before the dismiss would leave the user with no card at all.
        stubUnconfirmedCard(requested = 2.0, confirmed = 1.0)
        val stale = cardOnScreen
        val fresh = stale
        stubPostReturns(fresh)
        val bolus = inProgressBolus(requested = 2.0, delivered = 1.0).apply {
            unconfirmedWarning = stale
            unconfirmedWarningDose = 0.5
        }

        sut.refreshUnconfirmedWarning(bolus)

        val order = inOrder(apexNotifications)
        order.verify(apexNotifications).dismiss(stale)
        order.verify(apexNotifications).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED), eq("asked 2.0 U, got 1.0 U"),
            eq(Notification.URGENT), eq(CoreUiR.raw.boluserror)
        )
        assertThat(bolus.unconfirmedWarning).isEqualTo(fresh)
    }

    /** Nothing new to say, so the card stays exactly as it is and the alarm ramp does not restart. */
    @Test
    fun aCardThatAlreadyNamesTheseFiguresIsLeftAlone() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.5)
        val card = cardOnScreen
        val bolus = inProgressBolus(requested = 2.0, delivered = 0.5).apply {
            unconfirmedWarning = card
            unconfirmedWarningDose = 0.5
        }

        sut.refreshUnconfirmedWarning(bolus)

        verify(apexNotifications, never()).dismiss(any<ApexNotifications.Handle>())
        verifyNoCardPosted()
        assertThat(bolus.unconfirmedWarning).isEqualTo(card)
    }

    /**
     * A card the user swiped away, on a pump that has since reported a different amount.
     *
     * The card comes BACK. This version of AAPS has no read side to the notification store, so
     * "did the user put this away" cannot be asked and the card is treated as still standing. That is
     * the safe direction and the only one available: answering "they swiped it" instead would drop the
     * handle on every call and leave a settled bolus's warning on screen for good.
     */
    @Test
    fun aCardTheUserPutAwayComesBackWhenThePumpReportsADifferentAmount() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.5)
        val gone = cardOnScreen
        val fresh = ApexNotifications.Handle(78)
        stubPostReturns(fresh)
        val bolus = inProgressBolus(requested = 2.0, delivered = 0.5).apply {
            unconfirmedWarning = gone
            unconfirmedWarningDose = 0.25
        }

        sut.refreshUnconfirmedWarning(bolus)

        val order = inOrder(apexNotifications)
        order.verify(apexNotifications).dismiss(gone)
        order.verify(apexNotifications).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED), eq("asked 2.0 U, got 0.5 U"),
            eq(Notification.URGENT), eq(CoreUiR.raw.boluserror)
        )
        assertThat(bolus.unconfirmedWarning).isEqualTo(fresh)
        assertThat(bolus.unconfirmedWarningDose).isEqualTo(0.5)
    }

    /**
     * The abandoned-bolus case. Its record was dropped while a card stood, and the pump's history entry
     * has now proved the whole dose went in - so the card, which said none of it had, comes down.
     * The record still carries the failure flag set when it was abandoned, and that must not make the
     * card look settled on its own.
     */
    @Test
    fun anAbandonedBolusThePumpProvesWholeLosesItsCard() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 2.0)
        val card = cardOnScreen
        val abandoned = inProgressBolus(requested = 2.0, delivered = 0.0, failed = true).apply {
            unconfirmedWarning = card
            unconfirmedWarningDose = 0.0
        }

        sut.settleAbandonedWarning(abandoned, confirmedDose = 2.0)

        verify(apexNotifications).dismiss(card)
        // Taken down, not swapped for another one: without this the test would pass just as well if the
        // card had been re-posted, because an unstubbed post hands back the null the assertion below
        // expects.
        verifyNoCardPosted()
        assertThat(abandoned.unconfirmedWarning).isNull()
        assertThat(abandoned.failed).isFalse()
    }

    /** The same proof the other way: the card is right, but its figure is not, so it is replaced. */
    @Test
    fun anAbandonedBolusThePumpProvesShortGetsThePumpsFigure() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.5)
        val card = cardOnScreen
        stubPostReturns(card)
        val abandoned = inProgressBolus(requested = 2.0, delivered = 0.0, failed = true).apply {
            unconfirmedWarning = card
            unconfirmedWarningDose = 0.0
        }

        sut.settleAbandonedWarning(abandoned, confirmedDose = 0.5)

        val order = inOrder(apexNotifications)
        order.verify(apexNotifications).dismiss(card)
        order.verify(apexNotifications).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED), eq("asked 2.0 U, got 0.5 U"),
            eq(Notification.URGENT), eq(CoreUiR.raw.boluserror)
        )
        assertThat(abandoned.unconfirmedWarningDose).isEqualTo(0.5)
    }

    /**
     * The ending the whole feature exists for, and the one it used to miss. The pump ended the bolus on
     * an alarm that says nothing about the motor, so `failed` is set while nothing proves the insulin
     * did not go in - the driver keeps the pending sync on this very ending for that reason. Keyed on
     * `failed` alone, the card stayed silent here.
     */
    @Test
    fun aBolusEndedWithoutProofOfAMotorStopStillGetsACard() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.0)
        val fresh = cardOnScreen
        stubPostReturns(fresh)
        val bolus = inProgressBolus(requested = 2.0, delivered = 0.0, failed = true).apply {
            deliveryStopProven = false
        }

        sut.refreshUnconfirmedWarning(bolus)

        verify(apexNotifications).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED), eq("asked 2.0 U, got 0.0 U"),
            eq(Notification.URGENT), eq(CoreUiR.raw.boluserror)
        )
        assertThat(bolus.unconfirmedWarning).isEqualTo(fresh)
    }

    /** A proven motor stop is a bolus that did not happen: it has its own report, and needs no card. */
    @Test
    fun aBolusWithAProvenMotorStopGetsNoCard() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.0)
        val bolus = inProgressBolus(requested = 2.0, delivered = 0.0, failed = true)

        sut.refreshUnconfirmedWarning(bolus)

        verifyNoCardPosted()
        assertThat(bolus.unconfirmedWarning).isNull()
    }

    /**
     * The record is kept without waiting to see whether a card was raised, because on that ending the
     * card comes from a thread that has only just been woken. So settling can find no card at all - and
     * when the pump's entry then proves a partial delivery, the warning is raised here instead of being
     * lost with the race.
     */
    @Test
    fun anAbandonedBolusWithNoCardYetStillGetsOneWhenThePumpProvesItShort() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.5)
        val fresh = cardOnScreen
        stubPostReturns(fresh)
        val abandoned = inProgressBolus(requested = 2.0, delivered = 0.0, failed = true)

        sut.settleAbandonedWarning(abandoned, confirmedDose = 0.5)

        verify(apexNotifications).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED), eq("asked 2.0 U, got 0.5 U"),
            eq(Notification.URGENT), eq(CoreUiR.raw.boluserror)
        )
        assertThat(abandoned.unconfirmedWarning).isEqualTo(fresh)
    }

    /** Nothing was raised and nothing needs to be: the pump says the whole dose went in. */
    @Test
    fun anAbandonedBolusWithNoCardStaysSilentWhenThePumpProvesItWhole() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 2.0)
        val abandoned = inProgressBolus(requested = 2.0, delivered = 0.0, failed = true)

        sut.settleAbandonedWarning(abandoned, confirmedDose = 2.0)

        verifyNoCardPosted()
        verify(apexNotifications, never()).dismiss(any<ApexNotifications.Handle>())
        assertThat(abandoned.unconfirmedWarning).isNull()
    }

    /** A bolus the user stopped delivered less on purpose, and the pump said how much. */
    @Test
    fun aCancelledBolusRaisesNoCard() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.5)
        val bolus = inProgressBolus(requested = 2.0, delivered = 0.5, cancelled = true)

        sut.refreshUnconfirmedWarning(bolus)

        verifyNoCardPosted()
        assertThat(bolus.unconfirmedWarning).isNull()
    }

    /** The first look at a short bolus: no card yet, so one goes up with the figures as they stand. */
    @Test
    fun aShortBolusWithNoCardYetGetsOne() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.5)
        val fresh = ApexNotifications.Handle(78)
        stubPostReturns(fresh)
        val bolus = inProgressBolus(requested = 2.0, delivered = 0.5)

        sut.refreshUnconfirmedWarning(bolus)

        verify(apexNotifications).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED), eq("asked 2.0 U, got 0.5 U"),
            eq(Notification.URGENT), eq(CoreUiR.raw.boluserror)
        )
        assertThat(bolus.unconfirmedWarning).isEqualTo(fresh)
        assertThat(bolus.unconfirmedWarningDose).isEqualTo(0.5)
    }

    /** A hand-given bolus is always announced, even while another one is still unanswered. */
    @Test
    fun anUnconfirmedManualBolusIsAnnouncedEvenOverAStandingWarning() {
        stubUnconfirmedCard(requested = 2.0, confirmed = 0.75)

        sut.warnBolusUnconfirmed(2.0, 0.75, BS.Type.NORMAL)
        sut.warnBolusUnconfirmed(2.0, 0.75, BS.Type.NORMAL)

        verify(apexNotifications, times(2)).post(
            eq(ApexNotifications.BOLUS_UNCONFIRMED), eq("asked 2.0 U, got 0.75 U"),
            eq(Notification.URGENT), eq(CoreUiR.raw.boluserror)
        )
    }
}
