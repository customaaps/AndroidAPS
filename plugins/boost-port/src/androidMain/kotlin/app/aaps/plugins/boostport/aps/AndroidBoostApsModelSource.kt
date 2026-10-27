package app.aaps.plugins.boostport.aps

import android.content.Context
import dev.zacsweers.metro.*
import java.io.IOException

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class AndroidBoostApsModelSource(private val context: Context) : BoostApsModelSource {
    override fun readAsset(name: String): String? = try {
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: IOException) { null }
}
