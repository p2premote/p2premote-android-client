package top.p2premote.android;

/** Version policy returned for the Android client target. */
final class ClientVersionPolicy {
    final String latestVersion;
    final String minSupportedVersion;
    final String releaseNotes;

    ClientVersionPolicy(String latestVersion, String minSupportedVersion, String releaseNotes) {
        this.latestVersion = latestVersion == null ? "" : latestVersion;
        this.minSupportedVersion = minSupportedVersion == null ? "" : minSupportedVersion;
        this.releaseNotes = releaseNotes == null ? "" : releaseNotes;
    }

    boolean requiresForceUpdate(String currentVersion) {
        return compare(currentVersion, minSupportedVersion) < 0;
    }

    boolean hasUpdate(String currentVersion) {
        return compare(currentVersion, latestVersion) < 0;
    }

    static int compare(String left, String right) {
        long[] leftParts = numericParts(left);
        long[] rightParts = numericParts(right);
        for (int index = 0; index < 3; index++) {
            int compared = Long.compare(leftParts[index], rightParts[index]);
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static long[] numericParts(String version) {
        long[] result = new long[] {0, 0, 0};
        if (version == null) return result;
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("\\.", 4);
        for (int index = 0; index < Math.min(3, parts.length); index++) {
            StringBuilder digits = new StringBuilder();
            for (int charIndex = 0; charIndex < parts[index].length(); charIndex++) {
                char value = parts[index].charAt(charIndex);
                if (!Character.isDigit(value)) break;
                digits.append(value);
            }
            if (digits.length() == 0) continue;
            try {
                result[index] = Long.parseLong(digits.toString());
            } catch (NumberFormatException ignored) {
                result[index] = Long.MAX_VALUE;
            }
        }
        return result;
    }
}
