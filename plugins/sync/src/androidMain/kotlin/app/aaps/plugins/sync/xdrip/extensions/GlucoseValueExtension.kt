package app.aaps.plugins.sync.xdrip.extensions

import app.aaps.core.data.model.GV
import org.json.JSONObject

fun GV.toXdripJson(calibratedSgv: Double? = null): JSONObject =
    JSONObject()
        .put("device", sourceSensor.text)
        .put("mills", timestamp)
        .put("isValid", isValid)
        .put("mgdl", calibratedSgv?.takeIf { it.isFinite() && it > 0 } ?: value)
        .put("direction", trendArrow.text)

