package top.p2premote.android;

final class PasswordValidator {
    private PasswordValidator() {}

    static boolean isValid(String password) {
        if (password == null || password.length() < 6) {
            return false;
        }

        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < password.length(); i++) {
            char character = password.charAt(i);
            if (character >= '0' && character <= '9') {
                hasDigit = true;
            } else if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')) {
                hasLetter = true;
            }
        }
        return hasDigit && hasLetter;
    }
}
