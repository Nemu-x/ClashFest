package com.github.kr328.clash.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeTrafficPollingTest {
    @Test
    fun onlyRunningVisibleHomePollsTraffic() {
        assertTrue(shouldPollHomeTraffic(true, true, true))
        assertFalse(shouldPollHomeTraffic(true, true, false))
        assertFalse(shouldPollHomeTraffic(true, false, true))
        assertFalse(shouldPollHomeTraffic(false, true, true))
    }
}
