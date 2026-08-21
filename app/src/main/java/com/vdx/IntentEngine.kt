package com.vdx

sealed class VdxIntent {
    data class WhatsApp(val contact: String, val message: String) : VdxIntent()
    data class Call(val contact: String) : VdxIntent()
    data class Sms(val contact: String, val message: String) : VdxIntent()
    object ReadSms : VdxIntent()
    data class Uber(val destination: String) : VdxIntent()
    data class YouTube(val searchQuery: String) : VdxIntent()
    data class Email(val contact: String, val message: String) : VdxIntent()
    data class AppLaunch(val appName: String) : VdxIntent()
    object ReadScreen : VdxIntent()
    object Memory : VdxIntent()
    data class Unknown(val raw: String) : VdxIntent()
    data class Clarification(val action: String, val question: String) : VdxIntent()
}

object IntentEngine {

    fun parse(transcript: String): VdxIntent {
        val t = transcript.lowercase().trim()

        // WhatsApp: "whatsapp mom saying hello", "send whatsapp to john"
        val waRegex = Regex("""(?:whatsapp|whats\s*app|wa)\s+(?:to\s+)?(\w+)(?:\s+(?:saying|that|message)\s+(.+))?""")
        waRegex.find(t)?.let { m ->
            val contact = m.groupValues[1]
            val message = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: ""
            return VdxIntent.WhatsApp(contact, message)
        }

        // Call: "call mom", "phone john", "ring dad"
        val callRegex = Regex("""(?:call|phone|ring|dial)\s+(\w+)""")
        callRegex.find(t)?.let { m ->
            return VdxIntent.Call(m.groupValues[1])
        }

        // SMS: "sms mom saying hello", "text john hello", "send message to dad"
        val smsRegex = Regex("""(?:sms|text|send\s+message\s+to)\s+(\w+)(?:\s+(?:saying|that|message)\s+(.+))?""")
        smsRegex.find(t)?.let { m ->
            val contact = m.groupValues[1]
            val message = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: ""
            return VdxIntent.Sms(contact, message)
        }

        // Read SMS: "read messages", "read my sms", "check messages"
        if (t.contains("read") && (t.contains("message") || t.contains("sms") || t.contains("text"))) {
            return VdxIntent.ReadSms
        }

        // Read screen: "read screen", "what's on screen", "read this"
        if ((t.contains("read") && t.contains("screen")) ||
            t.contains("what's on screen") ||
            t.contains("whats on screen") ||
            t == "read this"
        ) {
            return VdxIntent.ReadScreen
        }

        // Uber: "book uber to airport", "get uber to hospital", "uber to school"
        val uberRegex = Regex("""(?:book|get|order)?\s*uber\s+(?:to\s+)?(.+)""")
        uberRegex.find(t)?.let { m ->
            val dest = m.groupValues[1].trim()
            if (dest.isNotBlank()) return VdxIntent.Uber(dest)
        }

        // YouTube: "youtube cat videos", "search youtube for cat videos", "play youtube cat videos"
        val ytRegex = Regex("""(?:search\s+)?youtube\s+(?:for\s+)?(.+)|(?:play\s+on\s+youtube\s+(.+))""")
        ytRegex.find(t)?.let { m ->
            val query = (m.groupValues.getOrNull(2) ?: m.groupValues[1]).trim()
            if (query.isNotBlank()) return VdxIntent.YouTube(query)
        }

        // Email: "email john saying hello", "send email to mom", "mail dad"
        val emailRegex = Regex("""(?:email|e-mail|mail)\s+(?:to\s+)?(\w+)(?:\s+(?:saying|that|message|subject)\s+(.+))?""")
        emailRegex.find(t)?.let { m ->
            val contact = m.groupValues[1]
            val message = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: ""
            return VdxIntent.Email(contact, message)
        }

        // App launch: "open facebook", "launch maps", "start spotify"
        val appRegex = Regex("""(?:open|launch|start)\s+(\w+)""")
        appRegex.find(t)?.let { m ->
            return VdxIntent.AppLaunch(m.groupValues[1])
        }

        // Memory: "what do you remember", "session memory", "recall"
        if (t.contains("memory") || t.contains("remember") || t.contains("recall") || t.contains("session")) {
            return VdxIntent.Memory
        }

        return VdxIntent.Unknown(transcript)
    }
}
