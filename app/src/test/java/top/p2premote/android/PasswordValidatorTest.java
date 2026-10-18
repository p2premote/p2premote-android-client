package top.p2premote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PasswordValidatorTest {
    @Test public void asciiBoundariesRequireActualLetterAndDigit() {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        String digits = "0123456789";
        for (char c = 0; c < 128; c++) {
            org.junit.Assert.assertEquals("letter candidate " + (int)c, letters.indexOf(c) >= 0,
                    PasswordValidator.isValid(c + "11111"));
            org.junit.Assert.assertEquals("digit candidate " + (int)c, digits.indexOf(c) >= 0,
                    PasswordValidator.isValid("aaaaa" + c));
        }
    }
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
        assertFalse(PasswordValidator.isValid("!!!!!!"));
        assertFalse(PasswordValidator.isValid("@[\\]^_"));
    }
}
