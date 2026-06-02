package com.kmnexus.codexmeter.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class IdsTest {
    @Test
    fun `provider id exposes raw value`() {
        assertEquals("codex", ProviderId("codex").value)
    }
}
