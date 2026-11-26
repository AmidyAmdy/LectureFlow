import pytest

from lectureflow_ml.chunks import split_segments


def segment(start, end):
    return {"start": start, "end": end, "text": f"Сегмент {start}"}


def test_last_incomplete_chunk_and_segment_identity():
    segments = [
        segment(0, 54),
        segment(54, 65),
        segment(65, 120),
        segment(120, 182),
        segment(182, 195),
    ]
    chunks = split_segments(segments, chunk_seconds=60)
    assert [[s["end"] for s in chunk] for chunk in chunks] == [
        [54, 65],
        [120],
        [182],
        [195],
    ]
    flattened = [s for chunk in chunks for s in chunk]
    assert len(flattened) == len(segments)
    assert all(actual is original for actual, original in zip(flattened, segments))


def test_long_segment_crosses_multiple_boundaries():
    segments = [segment(0, 400), segment(400, 410)]
    assert split_segments(segments, 180) == [[segments[0]], [segments[1]]]


def test_empty_and_exact_boundary():
    assert split_segments([]) == []
    assert split_segments([segment(0, 180)]) == [[segment(0, 180)]]


@pytest.mark.parametrize("duration", [0, -1, float("nan"), float("inf")])
def test_invalid_duration(duration):
    with pytest.raises(ValueError):
        split_segments([], duration)
