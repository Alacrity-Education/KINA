package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** Real JLCPCB rows (2026-10-05): the Panasonic part is listed with stock 0, the Vishay one in stock (made up). */
    static final List<JlcpcbRow> THIN_FILM = List.of(
            JlcpcbTestDatabase.row("C2075020", "Resistors", "Chip Resistor - Surface Mount", "ERA-6AEB5361V", "0805",
                    "PANASONIC", "Extended", "125mW Thin Film Resistor 100V ±0.1% ±25ppm/℃ 5.36kΩ", "", "0"),
            JlcpcbTestDatabase.row("C1854569", "Resistors", "Chip Resistor - Surface Mount", "TNPW08055K36BEEA", "0805",
                    "Vishay Intertech", "Extended", "-55℃~+155℃ 125mW 150V 5.36kΩ Thin Film Resistor ±0.1% ±25ppm/℃",
                    "1-:0.20", "350"));

    @Test
    void lookupTellsOutOfStockFromNotFoundAndMatchesManufacturerPartNumbers() throws Exception {
        List<JlcpcbRow> rows = new ArrayList<>(JlcpcbTestDatabase.SAMPLE);
        rows.addAll(THIN_FILM);
        JlcpcbSqliteSearch search = new JlcpcbSqliteSearch(JlcpcbTestDatabase.create(dir.resolve("parts-fts5.db"),
                rows));
        LcscClient client = new LcscClient(search);

        PartLookupResult byNumber = client.lookup("C2075020", Deadline.immediate());
        assertThat(byNumber.status()).isEqualTo(PartLookupResult.Status.OUT_OF_STOCK);
        assertThat(byNumber.asOptional()).isEmpty();
        // the listed part, stock 0: returned only for an explicitly requested part number (DESIGN.md 2)
        assertThat(byNumber.listed()).hasValueSatisfying(p -> {
            assertThat(p.distributorPartNumber()).isEqualTo("C2075020");
            assertThat(p.stock()).isZero();
        });
        assertThat(byNumber.identity()).isEqualTo(new PartLookupResult.Identity("C2075020", "PANASONIC",
                "ERA-6AEB5361V", "125mW Thin Film Resistor 100V ±0.1% ±25ppm/℃ 5.36kΩ"));

        // TME's spelling of the Panasonic MPN finds the hyphenated JLCPCB row
        PartLookupResult byMpn = client.lookup("ERA6AEB5361V", Deadline.immediate());
        assertThat(byMpn.status()).isEqualTo(PartLookupResult.Status.OUT_OF_STOCK);
        assertThat(byMpn.identity().partNumber()).isEqualTo("C2075020");

        PartLookupResult inStock = client.lookup("tnpw0805-5k36-beea", Deadline.immediate());
        assertThat(inStock.status()).isEqualTo(PartLookupResult.Status.FOUND);
        assertThat(inStock.part().distributorPartNumber()).isEqualTo("C1854569");
        assertThat(inStock.part().stock()).isEqualTo(350);

        assertThat(client.lookup("C424242", Deadline.immediate()).status())
                .isEqualTo(PartLookupResult.Status.NOT_FOUND);
        assertThat(client.lookup("ERA6AEB5362V", Deadline.immediate()).status())
                .isEqualTo(PartLookupResult.Status.NOT_FOUND);
        assertThat(client.getPart("C2075020")).isEmpty();   // the stock rule: never a Part without stock
        search.close();
    }

    @Test
    void mpnMatchExpressionCoversEverySeparatorPhase() {
        assertThat(JlcpcbSqliteSearch.mpnMatchExpression("ERA6AEB5361V")).isEqualTo("\"MFR.Part\" : ("
                + "(\"ERA\" AND \"6AE\" AND \"B53\" AND \"61V\") OR (\"RA6\" AND \"AEB\" AND \"536\") OR "
                + "(\"A6A\" AND \"EB5\" AND \"361\"))");
        assertThat(JlcpcbSqliteSearch.mpnMatchExpression("AB-12")).isNull();
        assertThat(JlcpcbSqliteSearch.mpnMatchExpression(null)).isNull();
    }
}
