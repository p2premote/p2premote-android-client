package top.p2premote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VerificationCodeValidatorTest {
    @Test
    public void acceptsExactlySixAsciiDigits() {
        assertTrue(VerificationCodeValidator.isValidEmailCode("012345"));
    }

    @Test
    public void rejectsInvalidCodes() {
        assertFalse(VerificationCodeValidator.isValidEmailCode(null));
        assertFalse(VerificationCodeValidator.isValidEmailCode("12345"));
        assertFalse(VerificationCodeValidator.isValidEmailCode("1234567"));
        assertFalse(VerificationCodeValidator.isValidEmailCode("12345a"));
        assertFalse(VerificationCodeValidator.isValidEmailCode("１２３４５６"));
    }
}
