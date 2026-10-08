package org.arpeon.domain.time;

import java.math.BigInteger;

/** Exact duration in whole notes. */
public record Duration(long numerator, long denominator) implements Comparable<Duration> {
  public Duration {
    if (numerator <= 0 || denominator <= 0)
      throw new IllegalArgumentException("Duration must be positive");
    long gcd = BigInteger.valueOf(numerator).gcd(BigInteger.valueOf(denominator)).longValueExact();
    numerator /= gcd;
    denominator /= gcd;
  }

  public Duration plus(Duration other) {
    var n =
        BigInteger.valueOf(numerator)
            .multiply(BigInteger.valueOf(other.denominator))
            .add(BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator)));
    var d = BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(other.denominator));
    var gcd = n.gcd(d);
    return new Duration(n.divide(gcd).longValueExact(), d.divide(gcd).longValueExact());
  }

  public Duration minus(Duration other) {
    var n =
        BigInteger.valueOf(numerator)
            .multiply(BigInteger.valueOf(other.denominator))
            .subtract(
                BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator)));
    if (n.signum() <= 0) throw new IllegalArgumentException("Duration remainder must be positive");
    var d = BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(other.denominator));
    var gcd = n.gcd(d);
    return new Duration(n.divide(gcd).longValueExact(), d.divide(gcd).longValueExact());
  }

  @Override
  public int compareTo(Duration other) {
    return BigInteger.valueOf(numerator)
        .multiply(BigInteger.valueOf(other.denominator))
        .compareTo(BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator)));
  }
}
