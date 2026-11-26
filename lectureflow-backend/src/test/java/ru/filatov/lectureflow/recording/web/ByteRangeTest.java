package ru.filatov.lectureflow.recording.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ByteRangeTest {

    private static final long SIZE = 1000;

    @Test
    void noHeaderMeansWholeFile() {
        assertThat(ByteRange.parse(null, SIZE)).isNull();
    }

    @Test
    void closedRange() {
        assertThat(ByteRange.parse("bytes=100-199", SIZE)).isEqualTo(new ByteRange(100, 199));
        assertThat(new ByteRange(100, 199).length()).isEqualTo(100);
    }

    @Test
    void openRangeGoesToEndOfFile() {
        assertThat(ByteRange.parse("bytes=900-", SIZE)).isEqualTo(new ByteRange(900, 999));
    }

    @Test
    void suffixRangeReturnsLastBytes() {
        assertThat(ByteRange.parse("bytes=-100", SIZE)).isEqualTo(new ByteRange(900, 999));
        assertThat(ByteRange.parse("bytes=-5000", SIZE)).isEqualTo(new ByteRange(0, 999));
    }

    @Test
    void endBeyondFileIsCut() {
        assertThat(ByteRange.parse("bytes=500-5000", SIZE)).isEqualTo(new ByteRange(500, 999));
    }

    @Test
    void startBeyondFileIsNotSatisfiable() {
        assertThatThrownBy(() -> ByteRange.parse("bytes=1000-", SIZE))
                .isInstanceOf(RangeNotSatisfiableException.class);
        assertThatThrownBy(() -> ByteRange.parse("bytes=-0", SIZE))
                .isInstanceOf(RangeNotSatisfiableException.class);
    }

    @Test
    void invalidOrMultipleRangesAreIgnored() {
        assertThat(ByteRange.parse("bytes=abc-", SIZE)).isNull();
        assertThat(ByteRange.parse("bytes=200-100", SIZE)).isNull();
        assertThat(ByteRange.parse("bytes=0-1,5-6", SIZE)).isNull();
        assertThat(ByteRange.parse("items=0-1", SIZE)).isNull();
    }
}
