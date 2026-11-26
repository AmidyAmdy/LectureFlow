package ru.filatov.lectureflow.recording;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecordingRepository extends JpaRepository<Recording, Long> {

    @Query("select r.audioObjectKey from Recording r where r.audioObjectKey is not null")
    List<String> findCurrentAudioObjectKeys();

    List<Recording> findByTranscriptionObjectKeyIsNotNullOrderByIdAsc();
}
