package app.aaps.plugins.boostport.telemetry

/**
 * Read-only KMP migration preview of legacy BoostV5AutoConfigApply's
 * schema-v2 factory-default amendments and one-time global "done" flag.
 *
 * The promoted 2026-07-06 build could incorrectly mark old-era factory
 * values as intentionally user-tuned. Legacy migration v2 reopens only
 * resolved keys still matching ANY era's factory default; true custom
 * settings remain resolved. The old global "done" flag migration does
 * the complementary operation: marks only genuinely tuned keys resolved.
 *
 * NO Preferences I/O, state mutation, engine registration, insulin
 * recommendations, ISF/SMB/basal modification or pump connection.
 */
internal object BoostV5SchemaMigrationAudit {
    const val CURRENT_SCHEMA_VERSION = 2

    private val managed = BoostV5ConfigResolutionAudit.Knob.entries.toSet()

    data class SchemaPreview(
        val wouldStampVersion: Int?,
        val wouldReopenResolvedKeys: Set<BoostV5ConfigResolutionAudit.Knob>,
        val wouldKeepResolvedKeys: Set<BoostV5ConfigResolutionAudit.Knob>,
    )

    data class LegacyFlagPreview(
        val wouldMarkResolvedKeys: Set<BoostV5ConfigResolutionAudit.Knob>,
        val wouldLeaveUnresolvedKeys: Set<BoostV5ConfigResolutionAudit.Knob>,
    )

    /** Null for corrupt input. Future schema versions must never be downgraded. */
    fun inspectSchema(
        storedSchemaVersion: Int,
        resolved: Set<BoostV5ConfigResolutionAudit.Knob>,
        stored: Map<BoostV5ConfigResolutionAudit.Knob, Double>,
    ): SchemaPreview? {
        if (storedSchemaVersion < 0 ||
            resolved.any { it !in managed } ||
            stored.any { (key, value) ->
                key !in managed || !value.isFinite() || value < 0.0
            }
        ) return null

        if (storedSchemaVersion >= CURRENT_SCHEMA_VERSION)
            return SchemaPreview(null, emptySet(), resolved.toSet())

        val reopen = resolved.filterTo(mutableSetOf()) { key ->
            !BoostV5ConfigResolutionAudit.isUserTuned(key, stored[key])
        }
        return SchemaPreview(
            wouldStampVersion = CURRENT_SCHEMA_VERSION,
            wouldReopenResolvedKeys = reopen.toSet(),
            wouldKeepResolvedKeys = (resolved - reopen).toSet(),
        )
    }

    /**
     * Source migrateLegacyDoneFlag is called only when the old global flag
     * is set. This preview exposes the decision but marks nothing.
     */
    fun inspectLegacyDoneFlag(
        legacyDoneFlag: Boolean,
        stored: Map<BoostV5ConfigResolutionAudit.Knob, Double>,
    ): LegacyFlagPreview? {
        if (stored.any { (key, value) ->
                key !in managed || !value.isFinite() || value < 0.0
            }
        ) return null
        if (!legacyDoneFlag)
            return LegacyFlagPreview(emptySet(), managed.toSet())

        val tuned = managed.filterTo(mutableSetOf()) { key ->
            BoostV5ConfigResolutionAudit.isUserTuned(key, stored[key])
        }
        return LegacyFlagPreview(
            wouldMarkResolvedKeys = tuned.toSet(),
            wouldLeaveUnresolvedKeys = (managed - tuned).toSet(),
        )
    }
}
