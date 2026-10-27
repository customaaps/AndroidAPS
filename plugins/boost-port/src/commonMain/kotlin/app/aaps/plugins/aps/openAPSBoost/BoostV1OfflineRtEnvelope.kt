package app.aaps.plugins.aps.openAPSBoost

/**
 * Unified read-only representation of the CURRENT partial V1 migration.
 *
 * The legacy RT is a dosing object. This envelope deliberately omits the
 * executable RT/APSResult interfaces and any insulin command fields. It can
 * be persisted beside a reference RT for offline comparisons only.
 */
internal object BoostV1OfflineRtEnvelope {
    enum class Finding {
        INVALID_PIPELINE, INVALID_EVENTUAL_BG, MISSING_EVENTUAL_BG,
        NO_SOURCE_EQUIVALENT_RESULT, INVALID_TARGET_ADJUSTMENT, INVALID_FUTURE_SENSITIVITY, INVALID_PREDICTION_GUARDS, INVALID_SAFETY_TIMELINE, SNAPSHOT_READY
    }

    data class Snapshot(
        val finding: Finding,
        val glucoseMgDl: Double?,
        val glucoseTimeMs: Long?,
        val dynamicIsf: Double?,
        val bgi: Double?,
        val deviation: Double?,
        val naiveEventualBg: Double?,
        val eventualBg: Double?,
        val iobPredictions: List<Int>,
        val cobPredictions: List<Int>,
        val zeroTempPredictions: List<Int>,
        val uamPredictions: List<Int>,
        val lgsThresholdMgDl: Double?,
        val lgsAccelerationOverride: Boolean,
        val smbConditionMatched: Boolean,
        val targetAdjustment: BoostV1HighBgTargetShadow.Report? = null,
        val expectedDelta: Double? = null,
        val futureSensitivityPath: BoostV1FutureSensitivitySelection.Path? = null,
        val futureSensitivityBg: Double? = null,
        val futureSensitivityIsf: Double? = null,
        val predictionGuard: BoostV1PredictionGuardBlend.Result? = null,
        val predictionSafety: BoostV1PredictionSafetyTimeline.Result? = null,
        val sourceEquivalentRtAvailable: Boolean = false,
        val eligibleForPumpDispatch: Boolean = false
    )

    fun inspect(
        input: BoostV1OfflineCalculationPipeline.Inputs,
        profileSensitivity: Double,
        highBgTarget: BoostV1HighBgTargetShadow.Input? = null,
        futureSensitivityInput: BoostV1FutureSensitivitySelection.Input? = null,
        predictionGuardInput: BoostV1PredictionGuardBlend.Input? = null,
        predictionSafetyInput: BoostV1PredictionSafetyTimeline.Input? = null
    ): Snapshot {
        val glucose = input.invocation.base.glucose
        fun rejected(finding: Finding) = Snapshot(
            finding = finding,
            glucoseMgDl = glucose.bgMgDl,
            glucoseTimeMs = glucose.glucoseTimeMs,
            dynamicIsf = null,
            bgi = null,
            deviation = null,
            naiveEventualBg = null,
            eventualBg = null,
            iobPredictions = emptyList(),
            cobPredictions = emptyList(),
            zeroTempPredictions = emptyList(),
            uamPredictions = emptyList(),
            lgsThresholdMgDl = null,
            lgsAccelerationOverride = false,
            smbConditionMatched = false
        )
        val report = BoostV1OfflineCalculationPipeline.inspect(input)
        if (report.finding != BoostV1OfflineCalculationPipeline.Finding.READY_FOR_REFERENCE_COMPARISON)
            return rejected(Finding.INVALID_PIPELINE)
        val isf = report.dynamicIsf ?: return rejected(Finding.INVALID_PIPELINE)
        val eventual = BoostV1PredictionEntry.calculate(
            input.invocation,
            selectedDynamicSensitivityMgDlPerUnit = isf,
            originalProfileSensitivityMgDlPerUnit = profileSensitivity
        )
        if (eventual.status == BoostV1PredictionEntry.Status.NO_GLUCOSE_DYNAMICS)
            return rejected(Finding.MISSING_EVENTUAL_BG)
        if (eventual.status != BoostV1PredictionEntry.Status.READY)
            return rejected(Finding.INVALID_EVENTUAL_BG)
        val calculated = eventual.calculated ?: return rejected(Finding.INVALID_EVENTUAL_BG)
        // The source adjusts targets after naive/eventual BG, then computes expectedDelta.
        val adjustment = highBgTarget?.let { target ->
            if (target.bg != glucose.bgMgDl ||
                target.eventualBg != calculated.eventualBg ||
                target.naiveEventualBg != calculated.naiveEventualBg)
                return rejected(Finding.INVALID_TARGET_ADJUSTMENT)
            BoostV1HighBgTargetShadow.inspect(target)
        }
        if (adjustment?.finding == BoostV1HighBgTargetShadow.Finding.INVALID_INPUT)
            return rejected(Finding.INVALID_TARGET_ADJUSTMENT)
        val expectedDelta = adjustment?.targetBg?.let { target ->
            val bgi = calculated.bgi ?: return rejected(Finding.INVALID_EVENTUAL_BG)
            val bg = calculated.eventualBg ?: return rejected(Finding.INVALID_EVENTUAL_BG)
            val expected = BoostV1ExpectedDeltaShadow.inspect(target, bg, bgi)
            if (expected.finding != BoostV1ExpectedDeltaShadow.Finding.READY)
                return rejected(Finding.INVALID_TARGET_ADJUSTMENT)
            expected.expectedDelta
        }
        val futureSelection = futureSensitivityInput?.let {
            if (it.currentBg != glucose.bgMgDl ||
                it.eventualBg != calculated.eventualBg ||
                it.mealCob != input.invocation.meal.mealCob)
                return rejected(Finding.INVALID_FUTURE_SENSITIVITY)
            BoostV1FutureSensitivitySelection.select(it)
                ?: return rejected(Finding.INVALID_FUTURE_SENSITIVITY)
        }
        val futureIsf = futureSelection?.let {
            val params = input.selectedSensitivity.copy(bgMgDl = it.glucoseForIsf, capEnabled = false)
            val result = BoostV1IsfShadowCalculation.inspect(params)
            if (result.finding != BoostV1IsfShadowCalculation.Finding.READY)
                return rejected(Finding.INVALID_FUTURE_SENSITIVITY)
            result.shadowIsf ?: return rejected(Finding.INVALID_FUTURE_SENSITIVITY)
        }
        val guard = predictionGuardInput?.let {
            if (it.mealCarbs != input.invocation.meal.carbs ||
                it.mealCob != input.invocation.meal.mealCob ||
                it.currentBg != glucose.bgMgDl ||
                (adjustment != null && it.targetBg != adjustment.targetBg) ||
                (report.lgsThresholdMgDl != null && it.threshold != report.lgsThresholdMgDl))
                return rejected(Finding.INVALID_PREDICTION_GUARDS)
            BoostV1PredictionGuardBlend.inspect(it)
                ?: return rejected(Finding.INVALID_PREDICTION_GUARDS)
        }
        val safety = predictionSafetyInput?.let { safetyInput ->
            val computedGuard = guard ?: return rejected(Finding.INVALID_SAFETY_TIMELINE)
            if (safetyInput.bg != glucose.bgMgDl ||
                safetyInput.mealCob != input.invocation.meal.mealCob ||
                safetyInput.naiveEventualBg != calculated.naiveEventualBg ||
                safetyInput.minGuardBg != computedGuard.minGuardBg ||
                safetyInput.smbConditionMatched != report.smbConditionMatched ||
                safetyInput.iobPredictions != report.predictionIob ||
                safetyInput.cobPredictions != report.predictionCob ||
                (report.lgsThresholdMgDl != null &&
                    safetyInput.lgsThreshold != report.lgsThresholdMgDl))
                return rejected(Finding.INVALID_SAFETY_TIMELINE)
            BoostV1PredictionSafetyTimeline.inspect(safetyInput).also {
                if (it.finding != BoostV1PredictionSafetyTimeline.Finding.READY)
                    return rejected(Finding.INVALID_SAFETY_TIMELINE)
            }
        }
        return Snapshot(
            finding = Finding.SNAPSHOT_READY,
            glucoseMgDl = glucose.bgMgDl,
            glucoseTimeMs = glucose.glucoseTimeMs,
            dynamicIsf = isf,
            bgi = calculated.bgi,
            deviation = calculated.deviation,
            naiveEventualBg = calculated.naiveEventualBg,
            eventualBg = calculated.eventualBg,
            iobPredictions = report.predictionIob,
            cobPredictions = report.predictionCob,
            zeroTempPredictions = report.predictionZt,
            uamPredictions = report.predictionUam,
            lgsThresholdMgDl = report.lgsThresholdMgDl,
            lgsAccelerationOverride = report.lgsAccelerationOverrideApplied,
            smbConditionMatched = report.smbConditionMatched,
            targetAdjustment = adjustment,
            expectedDelta = expectedDelta,
            futureSensitivityPath = futureSelection?.path,
            futureSensitivityBg = futureSelection?.glucoseForIsf,
            futureSensitivityIsf = futureIsf,
            predictionGuard = guard,
            predictionSafety = safety
        )
    }
}
