package ro.alacrity.kina.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.TestcontainersConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Migration V13 rewrites TME product URLs cached with an encoded {@code %2F} (DESIGN.md 9.2). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TmeProductUrlMigrationTest {

    private static final Path V13 = Path.of("src/main/resources/db/migration/V13__tme_product_url.sql");
    private static final String OLD = "https://www.tme.eu/en/details/DTMSS-20%2F0.010%2F20V/";
    private static final String NEW = "https://www.tme.eu/en/details/dtmss-20_0.010_20v/";
    private static final String PDF = "https://www.tme.eu/Document/abc/ABC.pdf";

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM cached_parts").update();
    }

    @Test
    void rewritesEncodedTmeProductUrlsAndTheProductPageDatasheet() throws IOException {
        insert("TME", "DTMSS-20/0.010/20V", OLD, OLD);
        insert("TME", "ABC/1", "https://www.tme.eu/en/details/ABC%2F1/", PDF);
        insert("TME", "1N4148", "https://www.tme.eu/en/details/1N4148/", PDF);
        insert("MOUSER", "M-X/1", "https://www.mouser.com/X%2F1", PDF);

        int updated = jdbc.sql(Files.readString(V13, StandardCharsets.UTF_8)).update();

        assertThat(updated).isEqualTo(2);
        assertThat(field("TME", "DTMSS-20/0.010/20V", "productUrl")).isEqualTo(NEW);
        assertThat(field("TME", "DTMSS-20/0.010/20V", "datasheetUrl")).isEqualTo(NEW);
        assertThat(field("TME", "ABC/1", "productUrl")).isEqualTo("https://www.tme.eu/en/details/abc_1/");
        assertThat(field("TME", "ABC/1", "datasheetUrl")).isEqualTo(PDF);
        assertThat(field("TME", "1N4148", "productUrl")).isEqualTo("https://www.tme.eu/en/details/1N4148/");
        assertThat(field("MOUSER", "M-X/1", "productUrl")).isEqualTo("https://www.mouser.com/X%2F1");
    }

    private void insert(String distributor, String partNumber, String productUrl, String datasheetUrl) {
        jdbc.sql("""
                INSERT INTO cached_parts (distributor, part_number, payload, stock_fetched_at, metadata_fetched_at,
                                          in_stock, type)
                VALUES (?, ?, jsonb_build_object('partNumber', ?::text, 'productUrl', ?::text,
                                                 'datasheetUrl', ?::text, 'stock', 5),
                        now(), now(), true, 'unknown')""")
                .params(distributor, partNumber, partNumber, productUrl, datasheetUrl).update();
    }

    private String field(String distributor, String partNumber, String name) {
        return jdbc.sql("SELECT payload ->> ? FROM cached_parts WHERE distributor = ? AND part_number = ?")
                .params(name, distributor, partNumber).query(String.class).single();
    }
}
