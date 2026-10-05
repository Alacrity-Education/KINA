package ro.alacrity.kina.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** One price bracket: unit price when ordering at least {@code quantity} pieces. */
public record PriceBreak(int quantity, BigDecimal unitPrice, String currency) {

    public PriceBreak {
        Objects.requireNonNull(unitPrice, "unitPrice");
    }
}
