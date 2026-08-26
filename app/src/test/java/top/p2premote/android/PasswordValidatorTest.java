package top.p2premote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PasswordValidatorTest {
    @Test
    public void acceptsPasswordContainingLettersAndNumbers() {
        assertTrue(PasswordValidator.isValid("abc123"));
        assertTrue(PasswordValidator.isValid("A1!@#$"));
    }

    @Test
    public void rejectsPasswordMissingRequiredComplexity() {
        assertFalse(PasswordValidator.isValid(null));
        assertFalse(PasswordValidator.isValid("a1"));
        assertFalse(PasswordValidator.isValid("abcdef"));
        assertFalse(PasswordValidator.isValid("123456"));
        assertFalse(PasswordValidator.isValid("密码密码1"));
    }
}
