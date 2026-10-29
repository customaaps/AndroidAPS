package app.aaps.plugins.boostport.aps

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/**
 * Metro treats T and T? as different binding keys. The common factory accepts an
 * optional platform source, so Android must explicitly adapt its non-null binding.
 * Reuse the scoped source; never create a second sensor owner.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object BoostActivitySourceBindings {
    @Provides
    fun optionalActivitySource(source: BoostApsActivitySource): BoostApsActivitySource? = source
}
