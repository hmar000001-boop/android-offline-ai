package dev.screenveil.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationPolicyTest {
    @Test fun ordinaryContentIsAllowed() {
        assertFalse(ModerationPolicy.shouldRedact(ContentDecision.ALLOW, redactUncertain = true))
    }

    @Test fun allSwimwearIsRedacted() {
        assertTrue(ModerationPolicy.shouldRedact(ContentDecision.SWIMWEAR, redactUncertain = false))
    }

    @Test fun suggestiveAndExplicitAreRedacted() {
        assertTrue(ModerationPolicy.shouldRedact(ContentDecision.SUGGESTIVE, redactUncertain = false))
        assertTrue(ModerationPolicy.shouldRedact(ContentDecision.EXPLICIT, redactUncertain = false))
    }

    @Test fun uncertainPolicyIsConfigurable() {
        assertTrue(ModerationPolicy.shouldRedact(ContentDecision.UNCERTAIN, redactUncertain = true))
        assertFalse(ModerationPolicy.shouldRedact(ContentDecision.UNCERTAIN, redactUncertain = false))
    }
}
