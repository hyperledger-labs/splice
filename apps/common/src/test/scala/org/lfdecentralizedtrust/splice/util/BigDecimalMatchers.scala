package org.lfdecentralizedtrust.splice.util

import org.scalactic.source
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.matchers.should.Matchers

import scala.math.BigDecimal.RoundingMode

trait BigDecimalMatchers extends Matchers {

  // Upper bound for fees in any of the above transfers
  // TODO(#806): Figure out something better for upper bounds of fees
  val smallAmount: BigDecimal = BigDecimal(1.0)

  def beWithin(lower: BigDecimal, upper: BigDecimal): Matcher[BigDecimal] =
    be >= lower and be <= upper

  def beAround(value: BigDecimal): Matcher[BigDecimal] =
    beWithin(value - smallAmount, value + smallAmount)

  def assertInRange(value: BigDecimal, range: (BigDecimal, BigDecimal))(implicit
      pos: source.Position
  ): Unit =
    value should beWithin(range._1, range._2)

  /** Asserts two BigDecimals are equal up to `n` decimal digits. */
  def beEqualUpTo(right: BigDecimal, n: Int): Matcher[BigDecimal] =
    Matcher { (left: BigDecimal) =>
      MatchResult(
        left.setScale(n, RoundingMode.HALF_EVEN) == right.setScale(n, RoundingMode.HALF_EVEN),
        s"$left was not equal to $right up to $n digits",
        s"$left was equal to $right up to $n digits",
      )
    }
}
