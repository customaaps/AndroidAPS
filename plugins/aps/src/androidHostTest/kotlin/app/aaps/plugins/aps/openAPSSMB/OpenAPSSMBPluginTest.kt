package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock

class OpenAPSSMBPluginTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var determineBasalSMB: DetermineBasalSMB
    @Mock lateinit var bgQualityCheck: BgQualityCheck
    @Mock lateinit var tddCalculator: TddCalculator
    @Mock lateinit var profiler: Profiler
    private lateinit var openAPSSMBPlugin: OpenAPSSMBPlugin

    @BeforeEach fun prepare() {
        openAPSSMBPlugin = OpenAPSSMBPlugin(
            aapsLogger, rxBus, constraintChecker, rh, profileFunction, profileUtil, config, activePlugin,
            iobCobCalculator, hardLimits, preferences, dateUtil, processedTbrEbData, persistenceLayer, glucoseStatusProvider,
            tddCalculator, bgQualityCheck, notificationManager, determineBasalSMB, profiler, GlucoseStatusCalculatorSMB(aapsLogger, iobCobCalculator, dateUtil, decimalFormatter, deltaCalculator), { apsResultProvider() }, ch,
            fabricPrivacy,
            dynamicCarbRatio = app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy.Disabled
        )
    }

    /** See the note in `OpenAPSAMAPluginTest`: construction is the coverage this class still carries. */
    @Test
    fun `the plugin constructs and is an APS`() {
        assertThat(openAPSSMBPlugin.getType()).isEqualTo(PluginType.APS)
    }

    /**
     * The 3.x velocity and BG cap settings were imported without their UI rows: the keys existed and
     * the algorithm read them, but no screen showed them, so a user could not change what the loop
     * used. This pins the rows to the screen and the values to the beta-3.4.2.6-apex reference.
     */
    @Test
    fun `the dyn isf velocity and bg cap settings appear in the smb screen`() {
        val items = openAPSSMBPlugin.getPreferenceScreenContent().items

        assertThat(items).contains(IntKey.ApsDynIsfVelocity)
        assertThat(items).contains(UnitDoubleKey.ApsDynIsfBgCap)
        // Order within the DynISF group follows the 3.x screen: adjustment factor, velocity, BG cap.
        assertThat(items.indexOf(IntKey.ApsDynIsfVelocity)).isGreaterThan(items.indexOf(IntKey.ApsDynIsfAdjustmentFactor))
        assertThat(items.indexOf(UnitDoubleKey.ApsDynIsfBgCap)).isGreaterThan(items.indexOf(IntKey.ApsDynIsfVelocity))

        assertThat(IntKey.ApsDynIsfVelocity.defaultValue).isEqualTo(30)
        assertThat(IntKey.ApsDynIsfVelocity.min).isEqualTo(1)
        assertThat(IntKey.ApsDynIsfVelocity.max).isEqualTo(200)
        assertThat(UnitDoubleKey.ApsDynIsfBgCap.defaultValue).isEqualTo(430.0)
        assertThat(UnitDoubleKey.ApsDynIsfBgCap.minMgdl).isEqualTo(108)
        assertThat(UnitDoubleKey.ApsDynIsfBgCap.maxMgdl).isEqualTo(450)
        // Hidden until DynamicISF itself is switched on, like every other DynISF row.
        assertThat(IntKey.ApsDynIsfVelocity.dependency).isEqualTo(BooleanKey.ApsUseDynamicSensitivity)
        assertThat(UnitDoubleKey.ApsDynIsfBgCap.dependency).isEqualTo(BooleanKey.ApsUseDynamicSensitivity)
    }
}
