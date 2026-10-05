package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import ro.alacrity.kina.TestcontainersConfiguration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JlcpcbDatabaseRepository.class})
class JlcpcbDatabaseRepositoryTest {

    @Autowired
    JlcpcbDatabaseRepository repository;

    @Test
    void emptyThenInsertThenReplace() {
        repository.delete();
        assertThat(repository.find()).isEmpty();

        Instant first = Instant.parse("2026-10-01T08:30:00.123456Z");
        repository.save(new JlcpcbDatabaseInfo("parts-fts5.db", "/data/jlcpcb/parts-fts5.db", first,
                1_000_000_000L, 7_000_000L, "2026-09-30"));
        assertThat(repository.find()).contains(new JlcpcbDatabaseInfo("parts-fts5.db", "/data/jlcpcb/parts-fts5.db",
                first, 1_000_000_000L, 7_000_000L, "2026-09-30"));

        Instant second = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.save(new JlcpcbDatabaseInfo("basic-parts-fts5.db", "/data/jlcpcb/basic-parts-fts5.db", second,
                null, null, null));
        JlcpcbDatabaseInfo row = repository.find().orElseThrow();
        assertThat(row.library()).isEqualTo("basic-parts-fts5.db");
        assertThat(row.downloadedAt()).isEqualTo(second);
        assertThat(row.sizeBytes()).isNull();
        assertThat(row.partCount()).isNull();
        assertThat(row.sourceDate()).isNull();
    }
}
