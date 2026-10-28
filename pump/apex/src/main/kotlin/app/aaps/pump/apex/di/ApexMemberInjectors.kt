package app.aaps.pump.apex.di

import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.pump.apex.ApexService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@BindingContainer
object ApexMemberInjectors {
    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(ApexService::class)
    fun service(injector: MembersInjector<ApexService>): MembersInjector<*> = injector
}
