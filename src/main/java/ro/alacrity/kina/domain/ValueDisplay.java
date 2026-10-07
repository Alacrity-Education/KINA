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

    /** The value, a space and the base unit: units written as words ({@code 3000 rpm}, {@code 25 dBA}). */
    final class Spaced implements ValueDisplay {

        @Override
        public String display(double value, Unit unit) {
            return format(value) + " " + unit.base();
        }
    }

    /**
     * The value in the base unit and in the {@link Unit#alternative()} unit in brackets, three significant digits each
     * ({@code 68 m³/h (40 CFM)}, {@code 24.5 Pa (2.5 mmH2O)}): one display rule whatever unit the text used.
     */
    final class WithAlternative implements ValueDisplay {

        @Override
        public String display(double value, Unit unit) {
            String base = significant(value) + " " + unit.base();
            String alternative = unit.alternative();
            for (int i = 0; i < unit.symbols().length && i < unit.factors().length; i++) {
                if (unit.symbols()[i].equalsIgnoreCase(alternative) && unit.factors()[i] > 0) {
                    return base + " (" + significant(value / unit.factors()[i]) + " " + alternative + ")";
                }
            }
            return base;
        }

        private static String significant(double v) {
            if (v == 0) {
                return "0";
            }
            return new BigDecimal(v).round(new MathContext(3)).stripTrailingZeros().toPlainString();
        }
    }

    /**
     * An ingress protection code from its two digits ({@code 67} is {@code IP67}; solids 0 are written {@code X}:
     * {@code 7} is {@code IPX7}).
     */
    final class IpCode implements ValueDisplay {

        @Override
        public String display(double value, Unit unit) {
            int code = (int) Math.round(value);
            int solids = code / 10;
            return unit.base() + (solids == 0 ? "X" : String.valueOf(solids)) + (code % 10);
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
