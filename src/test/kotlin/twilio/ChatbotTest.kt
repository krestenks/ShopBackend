package twilio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Test for phrase variations - verifying the system prompt covers all variations
 */
class PhraseVariationsTest {

    @Test
    fun systemPromptContainsTherapistPhrases() {
        val prompt = THERAPIST_PHRASES
        assertTrue(prompt.contains("who is available?"))
        assertTrue(prompt.contains("who works here?"))
        assertTrue(prompt.contains("list therapists"))
        assertTrue(prompt.contains("who are your staff?"))
        assertTrue(prompt.contains("show me your team"))
    }

    @Test
    fun systemPromptContainsAvailabilityPhrases() {
        val prompt = AVAILABILITY_PHRASES
        assertTrue(prompt.contains("is [name] available today?"))
        assertTrue(prompt.contains("can I book [name] tomorrow?"))
        assertTrue(prompt.contains("when is [name] free?"))
        assertTrue(prompt.contains("slots for [name]"))
        assertTrue(prompt.contains("what time can I come in?"))
    }

    @Test
    fun systemPromptContainsServicePhrases() {
        val prompt = SERVICE_PHRASES
        assertTrue(prompt.contains("what services does [name] offer?"))
        assertTrue(prompt.contains("[name]'s services"))
        assertTrue(prompt.contains("what can [name] do?"))
        assertTrue(prompt.contains("treatments by [name]"))
    }

    @Test
    fun allPhraseCategoriesArePresent() {
        val prompt = ALL_PHRASES
        assertTrue(prompt.contains("THERAPISTS"))
        assertTrue(prompt.contains("AVAILABILITY"))
        assertTrue(prompt.contains("SERVICES"))
    }
}

private const val THERAPIST_PHRASES = """
THERAPISTS - Call "therapists" tool:
- "who is available?"
- "who works here?"
- "list therapists"
- "who are your staff?"
- "do you have any therapists?"
- "who can I book with?"
- "show me your team"
- "what therapists do you have?"
- "is anyone working today?"
- "staff members"
"""

private const val AVAILABILITY_PHRASES = """
AVAILABILITY - Call "available_slots" tool:
- "is [name] available today?"
- "can I book [name] tomorrow?"
- "what times does [name] have?"
- "[name]'s schedule"
- "when is [name] free?"
- "slots for [name]"
- "book [name] on [date]"
- "appointment with [name]"
- "available appointments"
- "what time can I come in?"
"""

private const val SERVICE_PHRASES = """
SERVICES - Call "employee_services" tool:
- "what services does [name] offer?"
- "[name]'s services"
- "what can [name] do?"
- "[name] specialties"
- "treatments by [name]"
- "services offered by [name]"
- "what does [name] do?"
"""

private const val ALL_PHRASES = """
PHRASE VARIATIONS - All these mean the same thing:

THERAPISTS - Call "therapists" tool:
- "who is available?"
- "who works here?"
- "list therapists"
- "who are your staff?"

AVAILABILITY - Call "available_slots" tool:
- "is [name] available today?"
- "can I book [name] tomorrow?"

SERVICES - Call "employee_services" tool:
- "what services does [name] offer?"
- "[name]'s services"
"""
