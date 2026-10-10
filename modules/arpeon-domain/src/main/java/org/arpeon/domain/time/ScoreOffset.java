package org.arpeon.domain.time;

import java.math.BigInteger;

/** Exact non-negative position within a measure, expressed in whole notes. */
public record ScoreOffset(long numerator, long denominator) implements Comparable<ScoreOffset> {
  public ScoreOffset {
    if (numerator < 0 || denominator <= 0)
      throw new IllegalArgumentException("Score offset must be non-negative");
    long gcd = BigInteger.valueOf(numerator).gcd(BigInteger.valueOf(denominator)).longValueExact();
    if (gcd == 0) gcd = denominator;
    numerator /= gcd;
    denominator /= gcd;
  }

  public static ScoreOffset zero() {
    return new ScoreOffset(0, 1);
  }

  public ScoreOffset plus(Duration duration) {
    var numeratorSum =
        BigInteger.valueOf(numerator)
            .multiply(BigInteger.valueOf(duration.denominator()))
            .add(
                BigInteger.valueOf(duration.numerator()).multiply(BigInteger.valueOf(denominator)));
    var denominatorProduct =
        BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(duration.denominator()));
    var gcd = numeratorSum.gcd(denominatorProduct);
    return new ScoreOffset(
        numeratorSum.divide(gcd).longValueExact(), denominatorProduct.divide(gcd).longValueExact());
  }

  public ScoreOffset minus(Duration duration) {
    var numeratorDifference =
        BigInteger.valueOf(numerator)
            .multiply(BigInteger.valueOf(duration.denominator()))
            .subtract(
                BigInteger.valueOf(duration.numerator()).multiply(BigInteger.valueOf(denominator)));
    if (numeratorDifference.signum() < 0)
      throw new IllegalArgumentException("MusicXML backup moves before the measure start");
    var denominatorProduct =
        BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(duration.denominator()));
    var gcd = numeratorDifference.gcd(denominatorProduct);
    return new ScoreOffset(
        numeratorDifference.divide(gcd).longValueExact(),
        denominatorProduct.divide(gcd).longValueExact());
  }

  public Duration distanceTo(ScoreOffset later) {
    var difference =
        BigInteger.valueOf(later.numerator)
            .multiply(BigInteger.valueOf(denominator))
            .subtract(
                BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(later.denominator)));
    if (difference.signum() <= 0)
      throw new IllegalArgumentException("Offset distance must be positive");
    var denominatorProduct =
        BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(later.denominator));
    var gcd = difference.gcd(denominatorProduct);
    return new Duration(
        difference.divide(gcd).longValueExact(), denominatorProduct.divide(gcd).longValueExact());
  }

  @Override
  public int compareTo(ScoreOffset other) {
    return BigInteger.valueOf(numerator)
        .multiply(BigInteger.valueOf(other.denominator))
        .compareTo(BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator)));
  }
}
