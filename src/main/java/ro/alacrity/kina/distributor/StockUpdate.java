package ro.alacrity.kina.distributor;

import ro.alacrity.kina.domain.PriceBreak;

import java.util.List;

/**
 * Current ships-now stock and prices of one part ({@link DistributorClient#refreshStock}, DESIGN.md 3.2 "Stock
 * refresh").
 *
 * @param stock  pieces that ship now; 0 or less when the part sold out since it was cached
 * @param prices the complete price list, ascending by quantity (empty: keep the cached prices)
 */
public record StockUpdate(int stock, List<PriceBreak> prices) {

    public StockUpdate {
        prices = prices == null ? List.of() : List.copyOf(prices);
    }
}
