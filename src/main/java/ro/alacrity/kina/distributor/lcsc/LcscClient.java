package ro.alacrity.kina.distributor.lcsc;

import org.springframework.stereotype.Component;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * LCSC served from the local JLCPCB parts database. Needs no credentials, so it is always configured; while no
 * database has been downloaded every call fails with {@code UNAVAILABLE}.
 */
@Component
public class LcscClient implements DistributorClient {

    static final int MAX_PAGE_SIZE = 200;

    private final JlcpcbSqliteSearch search;
    private final Clock clock;

    public LcscClient(JlcpcbSqliteSearch search) {
        this.search = search;
        this.clock = Clock.systemUTC();
    }

    @Override
    public Distributor distributor() {
        return Distributor.LCSC;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public int maxPageSize() {
        return MAX_PAGE_SIZE;
    }

    @Override
    public DistributorSearchPage search(String query, int offset, int limit) {
        requireDatabase();
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int start = Math.max(0, offset);
        try {
            JlcpcbSqliteSearch.Result result = search.search(query, start, pageSize);
            Instant now = clock.instant();
            List<Part> parts = result.rows().stream()
                    .flatMap(row -> LcscPartMapper.map(row, now).stream())
                    .toList();
            boolean hasMore = start + result.rows().size() < result.total();
            return new DistributorSearchPage(parts, result.total(), hasMore);
        } catch (SQLException | IllegalStateException e) {
            throw failure(e);
        }
    }

    @Override
    public Optional<Part> getPart(String distributorPartNumber) {
        requireDatabase();
        try {
            return search.findByLcsc(distributorPartNumber).flatMap(row -> LcscPartMapper.map(row, clock.instant()));
        } catch (SQLException | IllegalStateException e) {
            throw failure(e);
        }
    }

    private void requireDatabase() {
        if (!search.isAvailable()) {
            throw new DistributorException(Distributor.LCSC, DistributorException.Kind.UNAVAILABLE,
                    "JLCPCB database not downloaded yet");
        }
    }

    private static DistributorException failure(Exception e) {
        return new DistributorException(Distributor.LCSC, DistributorException.Kind.UNAVAILABLE,
                "JLCPCB database query failed: " + e.getMessage(), e);
    }
}
