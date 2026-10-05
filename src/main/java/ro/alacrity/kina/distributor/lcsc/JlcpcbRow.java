package ro.alacrity.kina.distributor.lcsc;

/** One raw row of the JLCPCB {@code parts} FTS5 table (all columns as stored). */
public record JlcpcbRow(
        String lcscPart,
        String firstCategory,
        String secondCategory,
        String mfrPart,
        String packageName,
        String solderJoint,
        String manufacturer,
        String libraryType,
        String description,
        String datasheet,
        String price,
        String stock) {

    /** {@code Stock} as an integer; 0 when missing or not numeric. */
    public int stockQuantity() {
        if (stock == null) {
            return 0;
        }
        String digits = stock.trim();
        try {
            long value = Long.parseLong(digits);
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
        } catch (NumberFormatException e) {
            try {
                return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) Double.parseDouble(digits)));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }
}
