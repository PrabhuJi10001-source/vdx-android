package com.vdx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSuggestTest {

    @Test
    fun emptyQuery_returnsTopCatalog() {
        val rows = CommandSuggest.suggest("")
        assertEquals(listOf(
            "call Mom",
            "WhatsApp Mom saying I'll be late",
            "book Uber to the airport",
            "open YouTube and play bhajans",
            "message Ravi on WhatsApp that I'll be late",
            "book me an Uber home",
        ), rows)
    }

    @Test
    fun prefix_call() {
        val rows = CommandSuggest.suggest("ca")
        assertEquals("call Mom", rows.first())
        assertTrue(rows.any { it.lowercase().contains("cancel") })
    }

    @Test
    fun wordStart_uber() {
        val rows = CommandSuggest.suggest("uber")
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.lowercase().contains("uber") })
    }
}
