package app.aaps.core.interfaces.aps

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RtOverviewValuesTest {
    @Test fun `display snapshot survives storage and device status serialization`() {
        val snapshot = RT(runningDynamicIsf = true, profileIsfMgdl = 100.8, effectiveIsf = 82.8,
            profileCarbRatio = 10.0, carbRatio = 8.0)
        val restored = RT.deserialize(snapshot.serialize())
        assertThat(restored).isEqualTo(snapshot.copy(reason = restored.reason))
    }

    @Test fun `older results without display fields are still readable`() {
        val old = RT.deserialize("""{"runningDynamicIsf":false,"sensitivityRatio":1.2}""")
        assertThat(old.profileIsfMgdl).isNull()
        assertThat(old.effectiveIsf).isNull()
        assertThat(old.profileCarbRatio).isNull()
        assertThat(old.carbRatio).isNull()
        assertThat(old.sensitivityRatio).isEqualTo(1.2)
    }
}
