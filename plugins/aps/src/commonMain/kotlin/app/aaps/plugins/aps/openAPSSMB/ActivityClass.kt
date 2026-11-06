package app.aaps.plugins.aps.openAPSSMB

enum class ActivityClass(
    val isfMultiplier: Double,
    val targetBgOffsetMgdl: Double,
    val smbCapFactor: Double
) {
    AEROBIC    (isfMultiplier = 1.3, targetBgOffsetMgdl = 10.0, smbCapFactor = 0.7),
    RESISTANCE (isfMultiplier = 1.0, targetBgOffsetMgdl = 15.0, smbCapFactor = 0.8),
    REST       (isfMultiplier = 1.0, targetBgOffsetMgdl =  0.0, smbCapFactor = 1.0)
}
