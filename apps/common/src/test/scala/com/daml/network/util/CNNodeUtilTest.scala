package com.daml.network.util

import com.daml.network.codegen.java.cc.amulet.Amulet
import com.daml.network.codegen.java.cc.fees.{ExpiringAmount, RatePerRound}
import com.daml.network.codegen.java.cc.types.Round
import com.digitalasset.canton.BaseTest
import java.math.BigDecimal
import org.scalatest.wordspec.AnyWordSpec

class CNNodeUtilTest extends AnyWordSpec with BaseTest {
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
    CNNodeUtil.holdingFee(amulet, 0L) shouldBe new BigDecimal(0.0).setScale(10)
    CNNodeUtil.holdingFee(amulet, 1L) shouldBe new BigDecimal(0.5).setScale(10)
    CNNodeUtil.holdingFee(amulet, 2L) shouldBe new BigDecimal(1.0).setScale(10)
    // Capped at initial amount
    CNNodeUtil.holdingFee(amulet, 3L) shouldBe new BigDecimal(1.0).setScale(10)
  }
  "compute current amount" in {
    CNNodeUtil.currentAmount(amulet, 0L) shouldBe new BigDecimal(1.0).setScale(10)
    CNNodeUtil.currentAmount(amulet, 1L) shouldBe new BigDecimal(0.5).setScale(10)
    CNNodeUtil.currentAmount(amulet, 2L) shouldBe new BigDecimal(0.0).setScale(10)
    // Capped at 0
    CNNodeUtil.currentAmount(amulet, 3L) shouldBe new BigDecimal(0.0).setScale(10)
  }
}
