package ro.alacrity.kina.distributor.lcsc;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.search.field.FieldQuery;
import ro.alacrity.kina.search.field.FieldSql;
import ro.alacrity.kina.search.field.SqliteFieldSql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The field query of LCSC (DESIGN.md 9.3 and 3.8): runs one relaxation step of a {@link FieldQuery} on the typed
 * in-stock table of the sidecar ({@code idx.part_index}, attached by {@link JlcpcbSqliteSearch}) and reads the rows of
 * the candidates from the FTS5 table by {@code fts_rowid}. Candidates come confirmed first, then the highest stock.
 */
@Component
public class LcscFieldSearch {

    /** The typed table of the sidecar, as the pool's connections see it. */
    private static final SqliteFieldSql SQL = SqliteFieldSql.INSTANCE;
    private static final SqliteFieldSql CONFIRMED = SqliteFieldSql.CONFIRMED;

    @Autowired private JlcpcbSqliteSearch search;
    @Autowired private Clock clock;

    /**
     * @param parts         candidates in order (at most the limit), mapped from the rows of the JLCPCB file
     * @param total         rows counted: the in-stock rows the step matches, or, when {@code confirmedOnly}, those of
     *                      them that state every requested attribute
     * @param confirmedOnly the confirmed rows alone filled the limit, so the rows with unstated attributes were not
     *                      searched (and are not in {@code total})
     * @param rows          the rows read (a row whose part cannot be mapped is not in {@code parts})
     */
    public record Candidates(List<Part> parts, int total, boolean confirmedOnly, int rows) {

        public Candidates {
            parts = List.copyOf(parts);
        }
    }

    /** True when the typed table of the current file is attached. */
    public boolean available() {
        return search.fieldIndexAvailable();
    }

    /**
     * The candidates of one step of {@code query} from {@code offset} (DESIGN.md 9.3): the rows that state every
     * requested attribute and match ({@link SqliteFieldSql#CONFIRMED}, an index seek) come first in the order of the
     * superset form, so while the chunk lies within them they are read with that form. Otherwise the superset
     * ({@link SqliteFieldSql#INSTANCE}: also the rows with unstated attributes, a scan of the family), confirmed first,
     * then the highest stock.
     *
     * @param maxWait the longest wait for a free connection (null: {@code kina.jlcpcb.pool-wait})
     * @throws SQLException          when the query fails or no connection became free in time
     * @throws IllegalStateException when no typed table is attached
     */
    public Candidates candidates(FieldQuery query, FieldQuery.Step step, int offset, int limit, Duration maxWait)
            throws SQLException {
        return search.withConnection(maxWait, c -> {
            if (search.fieldIndex() == null) {
                throw new IllegalStateException("no typed table attached");
            }
            int confirmed = count(c, CONFIRMED.count(query, step));
            if (limit > 0 && confirmed >= offset + limit) {
                return fetch(c, CONFIRMED.candidates(query, step, offset, limit), confirmed, true);
            }
            if (limit <= 0) {
                return new Candidates(List.of(), count(c, SQL.count(query, step)), false, 0);
            }
            return fetch(c, SQL.candidates(query, step, offset, limit), -1, false);
        });
    }

    /** The first {@code limit} candidates of one step (from offset 0). */
    public Candidates candidates(FieldQuery query, FieldQuery.Step step, int limit, Duration maxWait)
            throws SQLException {
        return candidates(query, step, 0, limit, maxWait);
    }

    private Candidates fetch(java.sql.Connection c, FieldSql.Statement select, int total, boolean confirmedOnly)
            throws SQLException {
        List<Long> order = new ArrayList<>();
        int counted = total;
        try (PreparedStatement ps = c.prepareStatement(select.sql())) {
            bind(ps, select.params());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    order.add(rs.getLong(1));
                    counted = rs.getInt(4);
                }
            }
        }
        if (counted < 0) {
            counted = 0;   // no row matched
        }
        Map<Long, JlcpcbRow> rows = rows(c, order);
        java.time.Instant now = clock.instant();
        List<Part> parts = new ArrayList<>(order.size());
        for (Long rowid : order) {
            JlcpcbRow row = rows.get(rowid);
            if (row != null) {
                LcscPartMapper.map(row, now).ifPresent(parts::add);
            }
        }
        return new Candidates(parts, counted, confirmedOnly, order.size());
    }

    private static int count(java.sql.Connection c, FieldSql.Statement count) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(count.sql())) {
            bind(ps, count.params());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** The rows of the FTS5 table by rowid (one statement for the whole page). */
    private static Map<Long, JlcpcbRow> rows(java.sql.Connection c, List<Long> rowids) throws SQLException {
        Map<Long, JlcpcbRow> out = new HashMap<>();
        if (rowids.isEmpty()) {
            return out;
        }
        String sql = "SELECT rowid, " + JlcpcbSqliteSearch.COLUMNS + " FROM parts WHERE rowid IN ("
                + String.join(", ", java.util.Collections.nCopies(rowids.size(), "?")) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < rowids.size(); i++) {
                ps.setLong(i + 1, rowids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1), JlcpcbSqliteSearch.row(rs, 1));
                }
            }
        }
        return out;
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }
}
