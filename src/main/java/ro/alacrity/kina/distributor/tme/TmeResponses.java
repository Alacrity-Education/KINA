package ro.alacrity.kina.distributor.tme;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * Jackson records for the TME API v2 responses used by KINA (schemas in {@code docs/vendor/tme-api-v2-openapi.json},
 * shapes confirmed against the live API on 2026-10-05). Every record ignores unknown properties; every field may be
 * null. Observed deviations from the OpenAPI document: {@code prices.tax.rate} is a number (documented as string),
 * an invalid bearer token answers HTTP 400 with {@code code=E_AUTH_TOKEN_IS_INVALID} (not 401).
 */
@UtilityClass
class TmeResponses {

    /** {@code POST /auth/token}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("refresh_token") String refreshToken) {

        @Override
        public String toString() {
            return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
        }
    }

    /** Error body, e.g. {@code {"code":"E_INPUT_PARAMS_VALIDATION_ERROR","error_code":6,"message":"Input data is not valid.","error_data":[{"field":"limit","message":"..."}]}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(
            String code,
            @JsonProperty("error_code") Integer errorCode,
            String message,
            @JsonProperty("error_data") JsonNode errorData) {
    }

    // ---- /products/search ------------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(String status, SearchData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchData(ProductList products, Counters counters) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductList(List<Product> elements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Counters(Integer pages, Integer count, Integer page) {
    }

    // ---- /products and /products/search elements -------------------------------------------------------------

    /**
     * One product. Observed {@code product_status} values (live, 2026-10-05): {@code HARDLY_AVAILABLE}, {@code NEW},
     * {@code CANNOT_BE_ORDERED}, {@code BLOCKED_FOR_ZBL_BACKORDERS}, {@code BLOCKED_FOR_ZBL_MP},
     * {@code MOQ_VALID_WHILE_STOCKS_LAST}, {@code ONLY_FOR_SPECIAL_ORDER}, {@code EXTERNAL_WAREHOUSE}; often empty.
     * {@code CANNOT_BE_ORDERED} parts had {@code stock_quantity = 0}; {@code EXTERNAL_WAREHOUSE} parts (symbols
     * ending in {@code -0}) report positive stock with large minimum order quantities.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Product(
            @JsonProperty("product_status") List<String> productStatus,
            String symbol,
            Category category,
            @JsonProperty("manufacturer_symbols") List<String> manufacturerSymbols,
            Manufacturer manufacturer,
            String description,
            BigDecimal multiples,
            @JsonProperty("minimal_amount") BigDecimal minimalAmount,
            Unit unit,
            Packing packing,
            Assets assets) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Category(Long id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manufacturer(Long id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Unit(String id, @JsonProperty("short_name") String shortName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Packing(List<PackingElement> elements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PackingElement(String id, BigDecimal amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Assets(@JsonProperty("primary_photo") Photo primaryPhoto) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Photo(String prime, String thumbnail, @JsonProperty("high_resolution") String highResolution) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductsResponse(String status, ProductsData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductsData(List<Product> elements) {
    }

    // ---- /products/data ----------------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DataResponse(String status, DataData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DataData(List<ProductData> elements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductData(
            @JsonProperty("stock_quantity") BigDecimal stockQuantity,
            String symbol,
            Unit unit,
            Prices prices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Prices(List<Price> elements, String currency, String type, Tax tax) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Price(BigDecimal amount, BigDecimal price, Boolean special) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Tax(String type, BigDecimal rate) {
    }

    // ---- /products/parameters ----------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParametersResponse(String status, ParametersData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParametersData(List<ProductParameters> elements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductParameters(String symbol, ParameterList parameters) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParameterList(List<Parameter> elements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Parameter(Long id, String name, List<ParameterValue> values) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParameterValue(Long id, String value) {
    }

    // ---- /products/files ---------------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FilesResponse(String status, FilesData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FilesData(List<ProductFiles> elements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductFiles(String symbol, Documents documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Documents(List<Document> elements) {
    }

    /**
     * A product document. Types per the OpenAPI document: INS manual, DTE documentation (datasheets), KCH safety data
     * sheet, GWA warranty, INB safety instructions, MOV video, YTB YouTube video, PRE presentation, SFT software.
     * Live responses also contain {@code LNK} (a small .txt file holding a link to the manufacturer page).
     * URLs are protocol-relative ({@code //www.tme.eu/Document/...}).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(
            String url,
            String type,
            Long size,
            @JsonProperty("file_name") String fileName,
            String language) {
    }
}
