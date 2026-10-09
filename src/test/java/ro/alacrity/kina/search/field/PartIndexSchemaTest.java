package ro.alacrity.kina.search.field;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.domain.Indexed.ColumnType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The columns of {@code part_index} are declared once (review A5): the structural columns of {@link PartIndexSql} and
 * the typed columns of the {@code @Indexed} declarations ({@link IndexColumn#declared()}). The migrations V14 and V16
 * create exactly these columns with these types, and the SQLite table of the LCSC sidecar is built from the same list;
 * both are compared in both directions, so a column the writer writes that the table lacks, or a column the table has
 * that nothing declares, fails here and not at runtime.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PartIndexSchemaTest {

    /** {@link ColumnType#postgres()} as {@code information_schema.columns.udt_name} reports it. */
    private static final Map<String, String> UDT = Map.of("text", "text", "text[]", "_text", "float8", "float8",
            "float8[]", "_float8", "smallint", "int2", "int", "int4", "boolean", "bool", "jsonb", "jsonb",
            "timestamptz", "timestamptz", "tsvector", "tsvector");

    @Autowired JdbcClient jdbc;

    @Test
    void theMigrationsCreateExactlyTheDeclaredColumns() {
        Map<String, String> expected = new LinkedHashMap<>();
        PartIndexSql.types().forEach((name, type) -> expected.put(name, UDT.get(type.postgres())));
        PartIndexSql.POSTGRES_ONLY.forEach((name, type) -> expected.put(name, UDT.get(type)));
        Map<String, String> actual = new LinkedHashMap<>();
        jdbc.sql("SELECT column_name, udt_name FROM information_schema.columns WHERE table_name = 'part_index'")
                .query((rs, n) -> actual.put(rs.getString(1), rs.getString(2))).list();
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void everyDeclaredColumnIsWrittenWithItsDeclaredType() {
        Map<String, ColumnType> written = PartIndexSql.types();
        IndexColumn.declared().forEach((name, type) -> assertThat(written).containsEntry(name, type));
        assertThat(IndexColumn.declared()).containsKeys("family", "package_key", "voltages_v", "capacitance_f",
                "impedance_test_hz", "usb_class", "rows_count");
        assertThat(IndexColumn.declared()).doesNotContainKey("attrs");
    }

    @Test
    void theSqliteTableHasTheSameColumns() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("fts_rowid", "INTEGER");
        PartIndexSql.types().forEach((name, type) -> expected.put(name, type.sqlite()));
        expected.put("payload_md5", "TEXT");
        expected.put("stock", "INTEGER");
        Map<String, String> actual = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            SqlitePartIndex.create(c, "part_index");
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(part_index)")) {
                while (rs.next()) {
                    actual.put(rs.getString("name"), rs.getString("type"));
                }
            }
        }
        assertThat(actual).containsExactlyEntriesOf(expected);
    }

    @Test
    void columnNamesMapToTheRowComponents() {
        assertThat(PartIndexSql.camelCase("package_key")).isEqualTo("packageKey");
        assertThat(PartIndexSql.camelCase("voltages_v")).isEqualTo("voltagesV");
        assertThat(PartIndexSql.COLUMNS).doesNotHaveDuplicates();
    }
}
