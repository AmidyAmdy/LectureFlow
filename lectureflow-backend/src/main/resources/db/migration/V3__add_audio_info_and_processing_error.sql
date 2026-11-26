ALTER TABLE recordings ADD COLUMN audio_content_type VARCHAR(100);
ALTER TABLE recordings ADD COLUMN audio_size BIGINT;

ALTER TABLE recordings ADD COLUMN failure_stage VARCHAR(100);
ALTER TABLE recordings ADD COLUMN failure_message TEXT;

UPDATE recordings
SET status = CASE
    WHEN summary_object_key IS NOT NULL THEN 'SUMMARIZED'
    WHEN transcription_object_key IS NOT NULL THEN 'TRANSCRIBED'
    WHEN audio_object_key IS NOT NULL THEN 'UPLOADED'
    ELSE 'NEW'
END
WHERE status IS NULL OR status NOT IN ('NEW', 'UPLOADED', 'TRANSCRIBED', 'SUMMARIZED', 'FAILED');

ALTER TABLE recordings ALTER COLUMN status SET NOT NULL;
ALTER TABLE recordings ADD CONSTRAINT recordings_status_check
    CHECK (status IN ('NEW', 'UPLOADED', 'TRANSCRIBED', 'SUMMARIZED', 'FAILED'));

UPDATE recordings SET title = 'Без названия' WHERE title IS NULL OR btrim(title) = '';
ALTER TABLE recordings ALTER COLUMN title SET NOT NULL;

CREATE INDEX recordings_created_at_idx ON recordings (created_at);
