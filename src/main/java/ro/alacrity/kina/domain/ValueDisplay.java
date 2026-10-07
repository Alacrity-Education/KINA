package ro.alacrity.kina.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;

/**
 * The compact display form of a value in its {@link Unit} ({@code 10uF}, {@code 4.7kohm}, {@code 250W}, {@code 5%},
 * {@code 2000h @105°C}), declared by {@link Unit#display()}. Implementations are stateless final classes with a public
 * no-argument constructor.
 */
public interface ValueDisplay {

    /** The value (in base units) as displayed. */
    String display(double value, Unit unit);

    /** The suffix of a test condition: the frequency of an impedance or ESR ({@code " @100MHz"}). */
    default String condition(double condition) {
        return " @" + PartAttribute.display(ParsedQuery.FREQUENCY, condition);
    }

    /** Six significant digits, no trailing zeros, no exponent. */
    static String format(double v) {
        return new BigDecimal(v).round(new MathContext(6)).stripTrailingZeros().toPlainString();
    }

    /**
     * The default: the largest of the unit's {@link Unit#prefixes()} the value reaches ({@code 0.25kW} is
     * {@code 250W} because power has the prefixes none and k; {@code 1500W} is {@code 1.5kW}).
     */
    final class Prefixed implements ValueDisplay {

        private static final Map<String, Double> MULTIPLIERS = Map.of("p", 1e-12, "n", 1e-9, "u", 1e-6, "m", 1e-3,
                "", 1.0, "k", 1e3, "M", 1e6, "G", 1e9);

        @Override
        public String display(double value, Unit unit) {
            if (value == 0) {
                return "0" + unit.base();
            }
            String[] prefixes = unit.prefixes();
            String chosen = prefixes[0];
            for (String p : prefixes) {
                if (Math.abs(value) >= MULTIPLIERS.get(p) * (1 - 1e-9)) {
                    chosen = p;
                }
            }
            return format(value / MULTIPLIERS.get(chosen)) + chosen + unit.base();
        }
    }

    /** A percentage ({@code 5%}, {@code 0.1%}). */
    final class Percent implements ValueDisplay {

        @Override
        public String display(double value, Unit unit) {
            return format(value) + unit.base();
        }
    }

    /** Hours, with the test temperature as the condition ({@code 2000h @105°C}). */
    final class HoursAtTemperature implements ValueDisplay {

        private final Prefixed hours = new Prefixed();

        @Override
        public String display(double value, Unit unit) {
            return hours.display(value, unit);
        }

        @Override
        public String condition(double condition) {
            return " @" + format(condition) + "°C";
        }
    }
}
