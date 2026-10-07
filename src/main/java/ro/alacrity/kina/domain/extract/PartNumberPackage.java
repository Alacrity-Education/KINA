package ro.alacrity.kina.domain.extract;

import ro.alacrity.kina.domain.AttributeLogic;
import ro.alacrity.kina.domain.ExtractionContext;
import ro.alacrity.kina.domain.PartSource;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The imperial chip code a chip resistor or capacitor part number states ({@code TNPW0805...}, {@code RC0805FR-07...},
 * {@code RN73C2A5K36BTDF}), conservatively: the last source of a passive's package, used only when neither the package
 * field, the attributes nor the description name one.
 */
public final class PartNumberPackage implements AttributeLogic {

    @Override
    public Optional<?> extract(PartSource part, Lookup lookup, ExtractionContext ctx) {
        return Optional.ofNullable(packageFromPartNumber(part.manufacturerPartNumber(), part.manufacturer()));
    }

    @Override
    public boolean readsAttributes() {
        return false;
    }

    /**
     * Series whose part number is {@code <series><imperial chip code>...} ({@code TNPW0805...}, {@code RC0805FR-07...},
     * {@code CRGCQ0805...}). Mined from the JLCPCB database (2026-10-05): every row of these prefixes with a chip
     * package (over 450 000 rows) states the same code as its {@code Package} column. Vishay {@code CRCW}, {@code TNPW},
     * {@code TNPU}, {@code RCP}, {@code RCS}, {@code RCG}, {@code RCWE}, {@code MCT}, {@code MCS}, {@code MCU},
     * {@code MCA}, {@code PAT}, {@code PLT}, {@code PLTT}, {@code PTN}, {@code WSL}, {@code VJ}; Yageo {@code RC},
     * {@code RT}, {@code AC}, {@code AT}, {@code AA}, {@code AF}, {@code AR}, {@code PE}, {@code PT}, {@code SR},
     * {@code RE}, {@code RL}, {@code RV}, {@code CC}, {@code CQ}; Stackpole {@code RNCF}, {@code RMCF}, {@code RMCS},
     * {@code RMCP}, {@code RMEF}, {@code RGC}, {@code RNCS}, {@code CSR}; TE {@code CPF}, {@code CRG}, {@code CRGH},
     * {@code CRGV}, {@code CRGCQ}. KEMET's {@code C0805C106K...} only for KEMET: TDK, iCM and Darfon write metric
     * codes after the same {@code C} ({@code C0603...} is a 0201 part).
     */
    private static final Set<String> CHIP_CODE_SERIES = Set.of("CRCW", "TNPW", "TNPU", "RCP", "RCS", "RCG", "RCWE",
            "MCT", "MCS", "MCU", "MCA", "PAT", "PLT", "PLTT", "PTN", "WSL", "VJ", "RC", "RT", "AC", "AT", "AA", "AF",
            "AR", "PE", "PT", "SR", "RE", "RL", "RV", "CC", "CQ", "RNCF", "RMCF", "RMCS", "RMCP", "RMEF", "RGC", "RNCS",
            "CSR", "CPF", "CRG", "CRGH", "CRGV", "CRGCQ");
    private static final Pattern MPN_CHIP_CODE = Pattern.compile(
            "^([A-Z]{1,5})(0201|0402|0603|0805|1206|1210|1812|2010|2512)");
    /**
     * Manufacturers that put metric size codes after a letter prefix (Samsung {@code RC0402...} = 01005, Susumu
     * {@code RT0603...} = 0201, TDK {@code C0603...}/{@code MLG0603...}, Taiyo Yuden {@code HK0603...}, Sunlord
     * {@code SDCL0603...}, Murata): never read a chip code from their part numbers.
     */
    private static final Pattern METRIC_CODE_MAKERS = Pattern.compile("(?i)samsung|tdk|susumu|taiyo|sunlord|murata");
    /**
     * TE RN73 thin film resistors: size letters after {@code RN73} and the TCR letter, e.g. {@code RN73C2A5K36BTDF}
     * (TE datasheet 1773270 "How To Order": 1E 0402, 1J 0603, 2A 0805, 2B 1206, 2E 1210, 2H 2010, 3A 2512; the JLCPCB
     * database agrees for all 172 000 rows of 1E/1J/2A/2B/2E).
     */
    private static final Pattern RN73 = Pattern.compile("^RN73[A-Z]?(1E|1J|2A|2B|2E|2H|3A)");
    private static final Map<String, String> RN73_SIZES = Map.of("1E", "0402", "1J", "0603", "2A", "0805",
            "2B", "1206", "2E", "1210", "2H", "2010", "3A", "2512");

    /**
     * The imperial chip code a chip resistor/capacitor part number states (conservative, see {@link #CHIP_CODE_SERIES}),
     * or null. Used only when neither the package field, the attributes nor the description name a package.
     */
    public static String packageFromPartNumber(String mpn, String manufacturer) {
        if (mpn == null || mpn.isBlank() || manufacturer != null && METRIC_CODE_MAKERS.matcher(manufacturer).find()) {
            return null;
        }
        String number = mpn.strip().toUpperCase(Locale.ROOT);
        Matcher rn73 = RN73.matcher(number);
        if (rn73.find()) {
            return RN73_SIZES.get(rn73.group(1));
        }
        Matcher m = MPN_CHIP_CODE.matcher(number);
        if (!m.find()) {
            return null;
        }
        String series = m.group(1);
        boolean kemet = "C".equals(series) && manufacturer != null
                && manufacturer.toLowerCase(Locale.ROOT).contains("kemet");
        return CHIP_CODE_SERIES.contains(series) || kemet ? m.group(2) : null;
    }
}
