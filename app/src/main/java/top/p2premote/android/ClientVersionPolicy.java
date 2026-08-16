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

    // Semantics must stay in sync with the desktop client's
    // core/src/update.rs is_version_less/version_parts: any number of
    // segments, missing segments default to 0, segments that fail to parse
    // (or overflow) are treated as 0. Changing either side requires
    // updating the other.
    static int compare(String left, String right) {
        long[] leftParts = numericParts(left);
        long[] rightParts = numericParts(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            long leftPart = index < leftParts.length ? leftParts[index] : 0;
            long rightPart = index < rightParts.length ? rightParts[index] : 0;
            int compared = Long.compare(leftPart, rightPart);
            if (compared != 0) return compared;
        }
        return 0;
    }

    // Mirrors version_parts in the desktop client's core/src/update.rs:
    // trim whitespace, strip leading v/V prefixes, split on '.' into any
    // number of segments, and keep only the leading ASCII digits of each
    // segment (so build suffixes like "1.7.9-f9fec8" yield 9). A segment
    // with no digits, or one whose digits overflow, is 0.
    private static long[] numericParts(String version) {
        if (version == null) return new long[0];
        String normalized = version.trim();
        while (!normalized.isEmpty()
                && (normalized.charAt(0) == 'v' || normalized.charAt(0) == 'V')) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("\\.");
        long[] result = new long[parts.length];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            int digitsEnd = 0;
            while (digitsEnd < part.length()
                    && part.charAt(digitsEnd) >= '0'
                    && part.charAt(digitsEnd) <= '9') {
                digitsEnd++;
            }
            if (digitsEnd == 0) continue;
            try {
                result[index] = Long.parseLong(part.substring(0, digitsEnd));
            } catch (NumberFormatException ignored) {
                result[index] = 0;
            }
        }
        return result;
    }
}
