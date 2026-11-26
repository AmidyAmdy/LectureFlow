package ru.filatov.lectureflow.recording.web;

public record ByteRange(long start, long end) {

    public long length() {
        return end - start + 1;
    }

    public static ByteRange parse(String header, long fileSize) {
        if (header == null || !header.startsWith("bytes=")) {
            return null;
        }
        String value = header.substring("bytes=".length()).trim();

        if (value.contains(",")) {
            return null;
        }
        int dash = value.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startText = value.substring(0, dash).trim();
        String endText = value.substring(dash + 1).trim();

        try {
            if (startText.isEmpty()) {

                if (endText.isEmpty()) {
                    return null;
                }
                long suffixLength = Long.parseLong(endText);
                if (suffixLength <= 0) {
                    throw new RangeNotSatisfiableException(fileSize);
                }
                long start = Math.max(0, fileSize - suffixLength);
                return new ByteRange(start, fileSize - 1);
            }

            long start = Long.parseLong(startText);
            long end = fileSize - 1;
            if (!endText.isEmpty()) {
                end = Long.parseLong(endText);
                if (end < start) {
                    return null;
                }

                end = Math.min(end, fileSize - 1);
            }
            if (start >= fileSize) {
                throw new RangeNotSatisfiableException(fileSize);
            }
            return new ByteRange(start, end);
        } catch (NumberFormatException e) {

            return null;
        }
    }
}
