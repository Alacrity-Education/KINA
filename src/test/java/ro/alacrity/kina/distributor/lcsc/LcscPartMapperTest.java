package ro.alacrity.kina.distributor.lcsc;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LcscPartMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Test
    void mapsEveryField() {
        JlcpcbRow row = new JlcpcbRow("C15851", "Capacitors", "Multilayer Ceramic Capacitors MLCC - SMD/SMT",
                "CL21B106KOQNNNE", "0805", "2", "Samsung Electro-Mechanics", "Basic", "10uF 16V X7R ±10%",
                "https://www.lcsc.com/datasheet/C15851.pdf", "1-199:0.020,200-599:0.016,600-:0.014", "800000");

        Part part = LcscPartMapper.map(row, NOW).orElseThrow();

        assertThat(part.distributor()).isEqualTo(Distributor.LCSC);
        assertThat(part.distributorPartNumber()).isEqualTo("C15851");
        assertThat(part.manufacturer()).isEqualTo("Samsung Electro-Mechanics");
        assertThat(part.manufacturerPartNumber()).isEqualTo("CL21B106KOQNNNE");
        assertThat(part.description()).isEqualTo("10uF 16V X7R ±10%");
        assertThat(part.category()).isEqualTo("Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT");
        assertThat(part.packageName()).isEqualTo("0805");
        assertThat(part.stock()).isEqualTo(800000);
        assertThat(part.minimumOrderQuantity()).isNull();
        assertThat(part.orderMultiple()).isNull();
        assertThat(part.prices()).hasSize(3);
        assertThat(part.prices().getFirst().quantity()).isEqualTo(1);
        assertThat(part.prices().getFirst().unitPrice()).isEqualByComparingTo(new BigDecimal("0.020"));
        assertThat(part.prices().getFirst().currency()).isEqualTo("USD");
        assertThat(part.datasheetUrl()).isEqualTo("https://www.lcsc.com/datasheet/C15851.pdf");
        assertThat(part.photoUrl()).isNull();
        assertThat(part.productUrl()).isEqualTo("https://www.lcsc.com/product-detail/C15851.html");
        assertThat(part.attributes()).isEmpty();
        assertThat(part.extra()).containsEntry("library_type", "Basic")
                .containsEntry("solder_joints", 2)
                .containsEntry("second_category", "Multilayer Ceramic Capacitors MLCC - SMD/SMT")
                .containsEntry("jlcpcb_url", "https://jlcpcb.com/partdetail/C15851");
        assertThat(part.extra().keySet()).containsExactly("library_type", "solder_joints", "second_category", "jlcpcb_url");
        assertThat(part.fetchedAt()).isEqualTo(NOW);
    }

    @Test
    void dropsRowsWithoutStock() {
        assertThat(LcscPartMapper.map(row("0"), NOW)).isEmpty();
        assertThat(LcscPartMapper.map(row(""), NOW)).isEmpty();
        assertThat(LcscPartMapper.map(row(null), NOW)).isEmpty();
        assertThat(LcscPartMapper.map(row("-5"), NOW)).isEmpty();
        assertThat(LcscPartMapper.map(row("12"), NOW)).isPresent();
    }

    @Test
    void toleratesMissingOptionalColumns() {
        JlcpcbRow row = new JlcpcbRow("C1", null, "Ferrite Beads", null, " ", null, null, null, null, null, "", "7");
        Part part = LcscPartMapper.map(row, NOW).orElseThrow();
        assertThat(part.category()).isEqualTo("Ferrite Beads");
        assertThat(part.packageName()).isNull();
        assertThat(part.prices()).isEmpty();
        assertThat(part.extra()).containsEntry("library_type", null).containsEntry("solder_joints", null);
    }

    private static JlcpcbRow row(String stock) {
        return new JlcpcbRow("C1", "A", "B", "M", "0402", "2", "X", "Basic", "d", null, "1-:0.1", stock);
    }
}
