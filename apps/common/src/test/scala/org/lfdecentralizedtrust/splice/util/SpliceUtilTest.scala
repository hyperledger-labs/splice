package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.Amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.fees.{ExpiringAmount, RatePerRound}
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import com.digitalasset.canton.BaseTest
import java.math.BigDecimal
import org.scalatest.wordspec.AnyWordSpec

class SpliceUtilTest extends AnyWordSpec with BaseTest {
  val amulet = new Amulet(
    "dso",
    "dso",
    new ExpiringAmount(
      new BigDecimal(1.0).setScale(10),
      new Round(0L),
      new RatePerRound(new BigDecimal(0.5).setScale(10)),
    ),
  )
  "compute holding fees" in {
    SpliceUtil.holdingFee(amulet, 0L) shouldBe new BigDecimal(0.0).setScale(10)
    SpliceUtil.holdingFee(amulet, 1L) shouldBe new BigDecimal(0.5).setScale(10)
    SpliceUtil.holdingFee(amulet, 2L) shouldBe new BigDecimal(1.0).setScale(10)
    // Capped at initial amount
    SpliceUtil.holdingFee(amulet, 3L) shouldBe new BigDecimal(1.0).setScale(10)
  }
}
