package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionArrayPostprocess
import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionArraysShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1PredictionArrayPostprocessTest {
    @Test fun trimFlatIobButRetainMinimum() {
        val arrays = BoostV1PredictionArraysShadow.Report(
            finding = BoostV1PredictionArraysShadow.Finding.READY,
            iob = List(20) { 110 }, zt = List(10) { 110 }
        )
        val r = BoostV1PredictionArrayPostprocess.inspect(
            BoostV1PredictionArrayPostprocess.Input(arrays, 110.0, 0.0, 0.0, 0.0, false)
        )
        assertEquals(BoostV1PredictionArrayPostprocess.Finding.READY, r.finding)
        assertEquals(13, r.iob.size)
        assertEquals(emptyList(), r.cob)
        assertFalse(r.canDeliverInsulin)
    }
}
