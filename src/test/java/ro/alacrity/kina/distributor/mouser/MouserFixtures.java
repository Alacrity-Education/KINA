package ro.alacrity.kina.distributor.mouser;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Recorded live Mouser responses under {@code src/test/resources/fixtures/mouser}. */
final class MouserFixtures {

    static final String KEYWORD = "keyword-10uf-x7r-0805.json";
    static final String PART_NUMBER = "partnumber-603-CC0805MKX77BB106.json";
    static final String ERROR_INVALID_KEY = "error-invalid-key.json";

    static final JsonMapper JSON = JsonMapper.builder().build();

    private MouserFixtures() {
    }

    static String text(String name) {
        try (InputStream in = MouserFixtures.class.getResourceAsStream("/fixtures/mouser/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static MouserSearchResponse response(String name) {
        return JSON.readValue(text(name), MouserSearchResponse.class);
    }
}
