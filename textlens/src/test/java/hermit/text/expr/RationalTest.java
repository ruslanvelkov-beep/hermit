package hermit.text.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class RationalTest {

    @Test
    void ofKeepsLowestTermsWithAPositiveDenominator() {
        Rational r = Rational.of(6, -8);
        assertEquals(BigInteger.valueOf(-3), r.numerator());
        assertEquals(BigInteger.valueOf(4), r.denominator());
        assertEquals(Rational.of(-3, 4), r);
        assertEquals(Rational.of(-3, 4).hashCode(), r.hashCode());
        assertEquals("-3/4", r.toString());
        assertEquals("5", Rational.of(10, 2).toString());
        assertThrows(ArithmeticException.class, () -> Rational.of(1, 0));
    }

    @Test
    void parseReadsDecimalsExactly() {
        assertEquals(Rational.of(19, 20), Rational.parse("0.95"));
        assertEquals(Rational.of(1, 2), Rational.parse(".5"));
        assertEquals(Rational.of(-5, 2), Rational.parse("\u22122.50"));
        assertEquals(Rational.of(1, 1_000_000_000), Rational.parse("1e-9"));
        assertEquals(Rational.of(2500, 1), Rational.parse("2.5E3"));
        assertEquals(Rational.of(3, 1), Rational.parse("+3"));
    }

    @Test
    void parseRefusesWhatIsNoDecimalNumeral() {
        for (String bad : new String[] {"3,500", "78k", "1 000", "", "-", "1e", "0x1F", "1e101"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Rational.parse(bad), bad);
            assertTrue(e.getMessage().contains(bad), e.getMessage());
        }
        assertEquals(Rational.of(1, 1).multiply(Rational.parse("1e100")), Rational.parse("1e+100"));
    }

    @Test
    void arithmeticIsExact() {
        Rational tenth = Rational.parse("0.1");
        Rational twoTenths = Rational.parse("0.2");
        assertEquals(Rational.parse("0.3"), tenth.add(twoTenths));
        assertEquals(Rational.parse("-0.1"), tenth.subtract(twoTenths));
        assertEquals(Rational.parse("0.02"), tenth.multiply(twoTenths));
        assertEquals(Rational.of(1, 2), tenth.divide(twoTenths));
        assertEquals(Rational.of(-1, 3), Rational.of(1, 3).negate());
        assertEquals(-1, Rational.of(-1, 3).signum());
        assertEquals(0, Rational.ZERO.signum());
        assertThrows(ArithmeticException.class, () -> Rational.ONE.divide(Rational.ZERO));
    }

    @Test
    void comparisonOrdersByValue() {
        assertTrue(Rational.of(1, 3).compareTo(Rational.parse("0.333")) > 0);
        assertTrue(Rational.parse("1.10").compareTo(Rational.parse("1.9")) < 0);
        assertEquals(0, Rational.parse("2.50").compareTo(Rational.of(5, 2)));
    }

    @Test
    void decimalStringIsExactOrNull() {
        assertEquals("0.125", Rational.of(1, 8).toDecimalString());
        assertEquals("-2.5", Rational.parse("-2.50").toDecimalString());
        assertEquals("0.000000001", Rational.parse("1e-9").toDecimalString());
        assertEquals("78000", Rational.parse("7.8e4").toDecimalString());
        assertEquals("0", Rational.parse("-0.0").toDecimalString());
        assertNull(Rational.of(1, 3).toDecimalString());
    }
}
