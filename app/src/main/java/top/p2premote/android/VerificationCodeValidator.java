package top.p2premote.android;

final class VerificationCodeValidator {
    private VerificationCodeValidator() {}

    static boolean isValidEmailCode(String value) {
        if (value == null || value.length() != 6) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
