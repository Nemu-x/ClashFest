package com.github.kr328.clash.service.util

import kotlin.test.Test
import kotlin.test.assertEquals

class TunStackResolverTest {
    private val auto = TunStackResolver.AUTO

    private fun resolve(setting: String, operator: String? = null, config: String? = null) =
        TunStackResolver.resolve(setting, operator, config)

    @Test fun explicitUserPick_isHonoured() {
        assertEquals("gvisor", resolve("gvisor"))
        assertEquals("mixed", resolve("mixed"))
        assertEquals("system", resolve("system"))
    }

    @Test fun auto_followsSubscriptionStack() {
        for (stack in listOf("gvisor", "system", "mixed", "lwip")) {
            assertEquals(stack, resolve(auto, config = "tun:\n  enable: true\n  stack: $stack\n"))
        }
    }

    @Test fun auto_isCaseInsensitive() {
        assertEquals("gvisor", resolve(auto, config = "tun:\n  stack: gVisor\n"))
    }

    @Test fun auto_missingOrUnknownStack_fallsBackToSystem() {
        assertEquals("system", resolve(auto, config = "proxies: []\n"))
        assertEquals("system", resolve(auto, config = "tun:\n  stack: nonsense\n"))
        assertEquals("system", resolve(auto, config = null))
        assertEquals("system", resolve(auto, config = ": : not yaml : :"))
    }

    @Test fun operatorHeader_locksOverUserAndSubscription() {
        // Operator wins over the user's manual pick...
        assertEquals("system", resolve("gvisor", operator = "system"))
        assertEquals("gvisor", resolve("system", operator = "gvisor"))
        // ...and over the subscription's declared stack under Auto.
        assertEquals("system", resolve(auto, operator = "system", config = "tun:\n  stack: gvisor\n"))
    }

    @Test fun operatorAuto_doesNotLock_defersToSettingOrSubscription() {
        assertEquals("gvisor", resolve("gvisor", operator = "auto"))
        assertEquals("mixed", resolve(auto, operator = "auto", config = "tun:\n  stack: mixed\n"))
        assertEquals("system", resolve(auto, operator = "auto", config = null))
    }

    @Test fun default_isSystem() {
        // ServiceStore's default value is "system", so this is the effective default path.
        assertEquals("system", resolve("system"))
        assertEquals("system", resolve("nonsense"))
    }
}
