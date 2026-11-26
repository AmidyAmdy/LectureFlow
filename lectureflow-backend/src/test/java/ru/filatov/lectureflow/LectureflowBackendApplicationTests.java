package ru.filatov.lectureflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ru.filatov.lectureflow.search.SearchService;
import ru.filatov.lectureflow.storage.AudioStorageService;

import javax.sql.DataSource;

@SpringBootTest(properties = {
        "lectureflow.s3.access-key=test",
        "lectureflow.s3.secret-key=test",
        "lectureflow.s3.endpoint=http://127.0.0.1:9",
        "lectureflow.opensearch.url=http://127.0.0.1:9",
        "spring.datasource.password=test",
        "spring.rabbitmq.password=test"
})
@Import(TestcontainersConfiguration.class)
class LectureflowBackendApplicationTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@MockitoBean
	private AudioStorageService audioStorageService;

	@MockitoBean
	private SearchService searchService;

	@Test
	void emptyDatabaseGetsAllMigrationsWithoutBaseline() {
		List<Map<String, Object>> history = jdbcTemplate.queryForList(
				"select version, type, success from flyway_schema_history order by installed_rank");
		assertThat(history).extracting(row -> row.get("version")).containsExactly("1", "2", "3");
		assertThat(history).extracting(row -> row.get("type")).containsOnly("SQL");
		assertThat(history).extracting(row -> row.get("success")).containsOnly(true);
	}

	@Test
	void existingDataSurvivesMigration() {

		Flyway oldSchema = Flyway.configure().dataSource(dataSource).schemas("legacy_check").target("2").load();
		oldSchema.migrate();
		jdbcTemplate.update("insert into legacy_check.recordings (title, status, audio_object_key, transcription_object_key, summary_object_key) "
				+ "values ('Готовая', 'SUMMARIZED', 'recordings/1/a.m4a', 'recordings/1/a/transcription.json', 'recordings/1/a/summaries.json')");
		jdbcTemplate.update("insert into legacy_check.recordings (title, status, audio_object_key) values (null, null, 'recordings/2/b.m4a')");
		jdbcTemplate.update("insert into legacy_check.recordings (title, status, audio_object_key, transcription_object_key) "
				+ "values ('Странный статус', 'PROCESSING', 'recordings/3/c.m4a', 'recordings/3/c/transcription.json')");

		Flyway.configure().dataSource(dataSource).schemas("legacy_check").load().migrate();

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"select title, status, audio_content_type, audio_size, summary_object_key from legacy_check.recordings order by id");
		assertThat(rows).extracting(row -> row.get("status")).containsExactly("SUMMARIZED", "UPLOADED", "TRANSCRIBED");
		assertThat(rows).extracting(row -> row.get("title")).containsExactly("Готовая", "Без названия", "Странный статус");
		assertThat(rows.get(0).get("summary_object_key")).isEqualTo("recordings/1/a/summaries.json");
		assertThat(rows.get(0).get("audio_content_type")).isNull();

		jdbcTemplate.execute("drop schema legacy_check cascade");
	}
}
