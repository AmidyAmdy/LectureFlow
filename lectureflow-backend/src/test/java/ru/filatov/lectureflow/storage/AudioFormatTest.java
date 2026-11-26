package ru.filatov.lectureflow.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AudioFormatTest {

    @Test
    void detectsFormatsByFirstBytes() {
        assertThat(AudioFormat.detect(bytes(0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '))).isEqualTo(AudioFormat.MP4);
        assertThat(AudioFormat.detect(bytes('I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 0, 0))).isEqualTo(AudioFormat.MP3);
        assertThat(AudioFormat.detect(bytes(0xFF, 0xFB, 0x90, 0x64, 0, 0, 0, 0, 0, 0, 0, 0))).isEqualTo(AudioFormat.MP3);
        assertThat(AudioFormat.detect(bytes(0xFF, 0xF1, 0x50, 0x80, 0, 0, 0, 0, 0, 0, 0, 0))).isEqualTo(AudioFormat.AAC);
        assertThat(AudioFormat.detect(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'))).isEqualTo(AudioFormat.WAV);
        assertThat(AudioFormat.detect(bytes('O', 'g', 'g', 'S', 0, 2, 0, 0, 0, 0, 0, 0))).isEqualTo(AudioFormat.OGG);
        assertThat(AudioFormat.detect(bytes('f', 'L', 'a', 'C', 0, 0, 0, 0x22, 0, 0, 0, 0))).isEqualTo(AudioFormat.FLAC);
        assertThat(AudioFormat.detect(bytes(0x1A, 0x45, 0xDF, 0xA3, 0, 0, 0, 0, 0, 0, 0, 0))).isEqualTo(AudioFormat.WEBM);
    }

    @Test
    void fileNameDoesNotMatterOnlyContent() {
        byte[] text = "это не аудио, а текст".getBytes(StandardCharsets.UTF_8);
        assertThat(AudioFormat.detect(text)).isNull();
        assertThat(AudioFormat.detect(new byte[] {1, 2})).isNull();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
