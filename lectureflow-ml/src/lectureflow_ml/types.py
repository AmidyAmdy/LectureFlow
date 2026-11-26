from typing import TypedDict


class Segment(TypedDict):
    start: float
    end: float
    text: str


class PartSummary(TypedDict):
    start: float
    end: float
    text: str
    elapsed_seconds: float
