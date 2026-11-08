package hermit.text.expr;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * An exact rational number, always in lowest terms with a positive denominator, so two equal values are equal objects
 * (milestone A3 of the annotation plan: the checkers' arithmetic is exact, never floating point).
 */
public final class Rational implements Comparable<Rational> {

    /** Zero. */
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    /** One. */
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);
    /** The largest exponent {@link #parse} accepts, either sign: a bound on the size of what the text can ask for. */
    public static final int MAX_EXPONENT = 100;

    private final BigInteger numerator;
    private final BigInteger denominator;

    Rational(BigInteger numerator, BigInteger denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
    }

    /**
     * The fraction {@code numerator / denominator} in lowest terms with a positive denominator; refused with an
     * ArithmeticException when the denominator is zero.
     */
    public static Rational fraction(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("zero denominator");
        }
        if (numerator.signum() == 0) {
            return ZERO;
        }
        BigInteger divisor = numerator.gcd(denominator);
        BigInteger reducedNumerator = numerator.divide(divisor);
        BigInteger reducedDenominator = denominator.divide(divisor);
        if (reducedDenominator.signum() < 0) {
            reducedNumerator = reducedNumerator.negate();
            reducedDenominator = reducedDenominator.negate();
        }
        if (reducedNumerator.equals(BigInteger.ONE) && reducedDenominator.equals(BigInteger.ONE)) {
            return ONE;
        }
        return new Rational(reducedNumerator, reducedDenominator);
    }

    /** The fraction {@code numerator / denominator}, as {@link #fraction}. */
    public static Rational of(long numerator, long denominator) {
        return fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    /**
     * The exact value of a decimal numeral: an optional sign ({@code -}, {@code +} or the minus sign U+2212), digits
     * with an optional fraction ({@code 3}, {@code 0.95}, {@code .5}), and an optional exponent ({@code 1e-9},
     * {@code 2.5E3}) of at most {@link #MAX_EXPONENT} either way. Anything else (thousands separators, suffixes,
     * spaces) is refused with an IllegalArgumentException that names the numeral.
     */
    public static Rational parse(String numeral) {
        if (numeral == null
                || !numeral.matches("[+\\-\\u2212]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+\\-]?[0-9]+)?")) {
            throw new IllegalArgumentException("Not a decimal numeral: " + numeral);
        }
        String value = numeral;
        boolean negative = value.startsWith("-") || value.startsWith("\u2212");
        if (value.startsWith("+") || negative) {
            value = value.substring(1);
        }
        int exponent = 0;
        int exponentIndex = Math.max(value.indexOf('e'), value.indexOf('E'));
        if (exponentIndex >= 0) {
            String exponentText = value.substring(exponentIndex + 1);
            try {
                exponent = Integer.parseInt(exponentText);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Not a decimal numeral: " + numeral, exception);
            }
            if (Math.abs(exponent) > MAX_EXPONENT) {
                throw new IllegalArgumentException("Not a decimal numeral: " + numeral);
            }
            value = value.substring(0, exponentIndex);
        }
        int point = value.indexOf('.');
        int fractionDigits = point < 0 ? 0 : value.length() - point - 1;
        String digits = point < 0 ? value : value.substring(0, point) + value.substring(point + 1);
        if (digits.isEmpty()) {
            digits = "0";
        }
        BigInteger numerator = new BigInteger(digits);
        if (negative) {
            numerator = numerator.negate();
        }
        int scale = fractionDigits - exponent;
        if (scale >= 0) {
            return fraction(numerator, BigInteger.TEN.pow(scale));
        }
        return fraction(numerator.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
    }

    /** The numerator, in lowest terms; its sign is the number's. */
    public BigInteger numerator() {
        return numerator;
    }

    /** The denominator, in lowest terms; always positive. */
    public BigInteger denominator() {
        return denominator;
    }

    /** This plus {@code other}. */
    public Rational add(Rational other) {
        BigInteger sum = numerator.multiply(other.denominator)
                .add(other.numerator.multiply(denominator));
        return fraction(sum, denominator.multiply(other.denominator));
    }

    /** This minus {@code other}. */
    public Rational subtract(Rational other) {
        BigInteger difference = numerator.multiply(other.denominator)
                .subtract(other.numerator.multiply(denominator));
        return fraction(difference, denominator.multiply(other.denominator));
    }

    /** This times {@code other}. */
    public Rational multiply(Rational other) {
        return fraction(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    /** This divided by {@code other}; an ArithmeticException when {@code other} is zero. */
    public Rational divide(Rational other) {
        return fraction(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }

    /** Minus this. */
    public Rational negate() {
        return numerator.signum() == 0 ? ZERO : new Rational(numerator.negate(), denominator);
    }

    /** -1, 0 or 1 as this is negative, zero or positive. */
    public int signum() {
        return numerator.signum();
    }

    /**
     * The exact decimal form ({@code 3}, {@code -0.125}, {@code 0.000000001}) without exponent and without trailing
     * zeros after the point, or null when the decimal does not terminate ({@code 1/3}).
     */
    public String toDecimalString() {
        BigInteger remaining = denominator;
        while (remaining.mod(BigInteger.TWO).signum() == 0) {
            remaining = remaining.divide(BigInteger.TWO);
        }
        BigInteger five = BigInteger.valueOf(5);
        while (remaining.mod(five).signum() == 0) {
            remaining = remaining.divide(five);
        }
        if (!remaining.equals(BigInteger.ONE)) {
            return null;
        }
        return new BigDecimal(numerator).divide(new BigDecimal(denominator)).stripTrailingZeros().toPlainString();
    }

    /** Orders by value. */
    @Override
    public int compareTo(Rational other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    /** Equal when the values are equal: both are kept in lowest terms with a positive denominator. */
    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Rational rational
                && numerator.equals(rational.numerator)
                && denominator.equals(rational.denominator);
    }

    /** A hash that equal values share. */
    @Override
    public int hashCode() {
        return 31 * numerator.hashCode() + denominator.hashCode();
    }

    /** {@code n} for a whole number, else {@code n/d} ({@code -3/4}). */
    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE) ? numerator.toString() : numerator + "/" + denominator;
    }
}
