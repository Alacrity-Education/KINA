package ro.alacrity.kina.distributor.lcsc;

import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses the JLCPCB {@code "Price"} column, e.g. {@code "1-199:0.013,200-599:0.011,600-:0.010"}, into ascending
 * {@link PriceBreak}s in USD. The lower bound of each range is the break quantity. Malformed brackets are skipped;
 * empty or garbage input yields an empty list. Never throws.
 */
public final class JlcpcbPriceParser {

    public static final String CURRENCY = "USD";

    private JlcpcbPriceParser() {
    }

    public static List<PriceBreak> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Map<Integer, BigDecimal> byQuantity = new TreeMap<>();
        for (String bracket : raw.split(",")) {
            int colon = bracket.lastIndexOf(':');
            if (colon <= 0) {
                continue;
            }
            String range = bracket.substring(0, colon).trim();
            String price = bracket.substring(colon + 1).trim();
            int dash = range.indexOf('-');
            String lower = (dash >= 0 ? range.substring(0, dash) : range).trim();
            try {
                int quantity = Integer.parseInt(lower);
                BigDecimal unitPrice = new BigDecimal(price);
                if (quantity <= 0 || unitPrice.signum() < 0) {
                    continue;
                }
                byQuantity.putIfAbsent(quantity, unitPrice);
            } catch (NumberFormatException e) {
                // skip malformed bracket
            }
        }
        List<PriceBreak> result = new ArrayList<>(byQuantity.size());
        byQuantity.forEach((q, p) -> result.add(new PriceBreak(q, p, CURRENCY)));
        result.sort(Comparator.comparingInt(PriceBreak::quantity));
        return List.copyOf(result);
    }
}
