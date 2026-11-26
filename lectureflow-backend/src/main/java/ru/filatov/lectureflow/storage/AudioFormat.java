package ru.filatov.lectureflow.storage;

public record AudioFormat(String extension, String contentType) {

    public static final int HEADER_SIZE = 12;

    public static final AudioFormat MP4 = new AudioFormat("m4a", "audio/mp4");
    public static final AudioFormat MP3 = new AudioFormat("mp3", "audio/mpeg");
    public static final AudioFormat WAV = new AudioFormat("wav", "audio/wav");
    public static final AudioFormat OGG = new AudioFormat("ogg", "audio/ogg");
    public static final AudioFormat FLAC = new AudioFormat("flac", "audio/flac");
    public static final AudioFormat WEBM = new AudioFormat("webm", "audio/webm");
    public static final AudioFormat AAC = new AudioFormat("aac", "audio/aac");

    public static AudioFormat detect(byte[] header) {
        if (header.length < 4) {
            return null;
        }

        if (header.length >= 8 && startsWith(header, 4, "ftyp")) {
            return MP4;
        }
        if (header.length >= 12 && startsWith(header, 0, "RIFF") && startsWith(header, 8, "WAVE")) {
            return WAV;
        }
        if (startsWith(header, 0, "OggS")) {
            return OGG;
        }
        if (startsWith(header, 0, "fLaC")) {
            return FLAC;
        }

        if (unsigned(header[0]) == 0x1A && unsigned(header[1]) == 0x45
                && unsigned(header[2]) == 0xDF && unsigned(header[3]) == 0xA3) {
            return WEBM;
        }

        if (startsWith(header, 0, "ID3")) {
            return MP3;
        }

        if (unsigned(header[0]) == 0xFF && (unsigned(header[1]) & 0xE0) == 0xE0) {
            int layer = (unsigned(header[1]) >> 1) & 0x03;

            if (layer == 0) {
                return AAC;
            }
            return MP3;
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int offset, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            if (data[offset + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }
}
