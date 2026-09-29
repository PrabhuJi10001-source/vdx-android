package com.vdx.sonic.voice

import com.vdx.sonic.IntentMode
import com.vdx.sonic.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppParseTest {

    private val parser = IntentParser()

    private fun typeOf(text: String): IntentType {
        val i = parser.parse(text)
        return i.type
    }

    private fun entitiesOf(text: String): Map<String, String> {
        return parser.parse(text).entities
    }

    @Test
    fun sendWhatsAppToMom_parsesAsWhatsApp() {
        val i = parser.parse("send whatsapp to mom")
        assertEquals(IntentType.WHATSAPP, i.type)
        assertTrue(i.entities["contact"]!!.equals("Mom", ignoreCase = true))
    }

    @Test
    fun sendWhatsAppToMomWithMessage_grabsMessage() {
        val i = parser.parse("send whatsapp to mom i would be late")
        assertEquals(IntentType.WHATSAPP, i.type)
        assertTrue(i.entities["contact"]!!.equals("Mom", ignoreCase = true))
        // message should contain the trailing text, not swallow it into the contact
        assertTrue(i.entities["message"].orEmpty().lowercase().contains("late"))
    }

    @Test
    fun sendItOnWhatsAppToDad_parses() {
        assertEquals(IntentType.WHATSAPP, typeOf("send it on whatsapp to dad"))
    }

    @Test
    fun openWhatsApp_stillAppLaunch() {
        // "open whatsapp" must remain APP_LAUNCH, not be hijacked to send
        assertEquals(IntentType.APP_LAUNCH, typeOf("open whatsapp"))
    }

    @Test
    fun callMomOnWhatsApp_isWhatsAppCallNotPhoneCall() {
        val i = parser.parse("call mom on WhatsApp")
        assertEquals(IntentType.WHATSAPP, typeOf("call mom on WhatsApp"))
        assertEquals(IntentMode.COMMAND, i.mode)
        assertEquals("call", i.entities["action"])
    }
}
