package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSBoost.replay.V1ReplayResult

/** Copies a completed candidate across the APS boundary; never turns invalid output into zero dosing. */
internal object BoostApsResultMapper {
    fun toRt(source: V1ReplayResult, input: BoostApsCalculator.Input): RT {
        val numbers = listOf(source.bg, source.eventualBG, source.targetBG, source.insulinReq, source.units,
            source.sensitivityRatio, source.rate, source.COB, source.IOB, source.variable_sens,
            source.carbRatio, source.dynamicISF, source.predictionISF, source.sensNormalTarget,
            source.tdd, source.tddRatio, source.insulinReqPctEffective, source.deltaAcceleration,
            source.mlHypoRisk, source.mlMealLikely, source.minGuardBG)
        require(numbers.all { it == null || it.isFinite() }) { "nonfinite Boost result" }
        require(source.units == null || source.units!! >= 0.0) { "negative Boost SMB" }
        require(source.rate == null || source.rate!! >= 0.0) { "negative Boost basal" }
        require((source.rate == null) == (source.duration == null)) { "incomplete Boost temp basal" }
        require(source.duration == null || source.duration!! >= 0) { "negative Boost duration" }
        require(source.timestamp == input.atMs) { "Boost result timestamp mismatch" }
        require(input.microBolusAllowed || source.units == null || source.units == 0.0) { "SMB constraint violated" }
        return RT(
            algorithm = APSResult.Algorithm.BOOST, runningDynamicIsf = source.runningDynamicIsf,
            timestamp = source.timestamp, temp = source.temp, bg = source.bg, tick = source.tick,
            eventualBG = source.eventualBG, targetBG = source.targetBG, insulinReq = source.insulinReq,
            carbsReq = source.carbsReq, carbsReqWithin = source.carbsReqWithin, units = source.units,
            deliverAt = source.deliverAt, sensitivityRatio = source.sensitivityRatio,
            reason = StringBuilder(source.reason.toString()), duration = source.duration, rate = source.rate,
            predBGs = source.predBGs?.let { it.copy(IOB = it.IOB?.toList(), ZT = it.ZT?.toList(),
                COB = it.COB?.toList(), aCOB = it.aCOB?.toList(), UAM = it.UAM?.toList()) },
            COB = source.COB, IOB = source.IOB, variable_sens = source.variable_sens,
            profileIsfMgdl = input.originalProfileIsf, effectiveIsf = source.dynamicISF ?: source.variable_sens,
            profileCarbRatio = input.originalProfileCr, carbRatio = source.carbRatio,
            carbRatioReason = source.carbRatioReason, boostTier = source.boostTier,
            boostActive = source.boostActive, fastCarbProtection = source.fastCarbProtection,
            dynamicISF = source.dynamicISF, predictionISF = source.predictionISF,
            sensNormalTarget = source.sensNormalTarget, tdd = source.tdd, tddRatio = source.tddRatio,
            insulinReqPctEffective = source.insulinReqPctEffective, deltaAcceleration = source.deltaAcceleration,
            boostProfileSwitch = source.boostProfileSwitch, mlHypoRisk = source.mlHypoRisk,
            mlMealLikely = source.mlMealLikely, minGuardBG = source.minGuardBG,
            consoleLog = source.consoleLog?.toMutableList(), consoleError = source.consoleError?.toMutableList(),
        )
    }
}
