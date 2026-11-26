package ru.filatov.lectureflow.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

@Service
public class AudioStorageService {

    private static final Logger log = LoggerFactory.getLogger(AudioStorageService.class);

    private final S3Client s3Client;
    private final String bucket;

    AudioStorageService(S3Client s3Client, @Value("${lectureflow.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    public String getBucket() {
        return bucket;
    }

    public boolean createBucketIfMissing() {
        try {
            s3Client.headBucket(builder -> builder.bucket(bucket));
            return false;
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(builder -> builder.bucket(bucket));
            return true;
        }
    }

    public void checkBucket() {
        s3Client.headBucket(builder -> builder.bucket(bucket));
    }

    public long getObjectSize(String key) {
        return s3Client.headObject(builder -> builder.bucket(bucket).key(key)).contentLength();
    }

    public void upload(String key, MultipartFile file, String contentType) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            s3Client.putObject(builder -> builder.bucket(bucket).key(key).contentType(contentType),
                    RequestBody.fromInputStream(inputStream, file.getSize()));
        }
    }

    public ResponseInputStream<GetObjectResponse> openRange(String key, long start, long end) {
        String range = "bytes=" + start + "-" + end;
        return s3Client.getObject(builder -> builder.bucket(bucket).key(key).range(range));
    }

    public byte[] readHeader(String key, int size) {
        String range = "bytes=0-" + (size - 1);
        return s3Client.getObjectAsBytes(builder -> builder.bucket(bucket).key(key).range(range)).asByteArray();
    }

    public String readText(String key) {
        return s3Client.getObjectAsBytes(builder -> builder.bucket(bucket).key(key)).asUtf8String();
    }

    public void delete(String key) {
        s3Client.deleteObject(builder -> builder.bucket(bucket).key(key));
    }

    public static List<String> objectKeysOfAudio(String audioKey) {
        String base = audioKey;
        int dot = audioKey.lastIndexOf('.');
        if (dot > audioKey.lastIndexOf('/')) {
            base = audioKey.substring(0, dot);
        }
        return List.of(audioKey, base + "/transcription.json", base + "/summaries.json");
    }

    public void deleteQuietly(String key) {
        try {
            delete(key);
        } catch (SdkException e) {
            log.warn("Не удалось удалить объект {} из RustFS: {}", key, e.getMessage());
        }
    }
}
