package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LcscClientTest {

    @TempDir
    Path dir;

    @Test
    void unavailableUntilDownloaded() {
        LcscClient client = new LcscClient(new JlcpcbSqliteSearch(dir.resolve("parts-fts5.db")));
        assertThat(client.distributor()).isEqualTo(Distributor.LCSC);
        assertThat(client.isConfigured()).isTrue();
        assertThat(client.maxPageSize()).isEqualTo(200);
        assertThatThrownBy(() -> client.search("10k", 0, 10))
                .isInstanceOfSatisfying(DistributorException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DistributorException.Kind.UNAVAILABLE);
                    assertThat(e.getMessage()).contains("JLCPCB database not downloaded yet");
                });
        assertThatThrownBy(() -> client.getPart("C1525")).isInstanceOf(DistributorException.class);
    }

    @Test
    void searchesAndPages() throws Exception {
        JlcpcbSqliteSearch search = new JlcpcbSqliteSearch(JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db")));
        LcscClient client = new LcscClient(search);

        DistributorSearchPage first = client.search("resistor", 0, 2);
        assertThat(first.parts()).hasSize(2);
        assertThat(first.totalResults()).isEqualTo(4);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.parts()).allSatisfy(p -> assertThat(p.stock()).isPositive());

        DistributorSearchPage last = client.search("resistor", 2, 500);
        assertThat(last.parts()).hasSize(2);
        assertThat(last.hasMore()).isFalse();

        assertThat(client.getPart("C1525")).get().extracting(Part::manufacturerPartNumber).isEqualTo("CL05B104KO5NNNC");
        assertThat(client.getPart("C99999")).isEmpty();   // known but out of stock
        search.close();
    }
}
