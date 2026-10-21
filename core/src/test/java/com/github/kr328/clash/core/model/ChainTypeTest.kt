package com.github.kr328.clash.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChainTypeTest {
    @Test fun engineChainTypesRemainGroupsAcrossCaseVariants() {
        for (wire in listOf("Chain", "chain", "CHAIN")) {
            val type = Json.decodeFromString<Proxy.Type>("\"$wire\"")
            assertEquals(Proxy.Type.Chain, type)
            assertTrue(type.group)
        }
    }

    @Test fun unknownFutureTypesStillDecodeSafely() {
        assertEquals(Proxy.Type.Unknown, Json.decodeFromString<Proxy.Type>("\"FutureProtocol\""))
    }
}
