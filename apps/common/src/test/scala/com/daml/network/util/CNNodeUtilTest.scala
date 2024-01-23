package com.daml.network.util

import com.daml.network.codegen.java.cc.coin.Coin
import com.daml.network.codegen.java.cc.fees.{ExpiringAmount, RatePerRound}
import com.daml.network.codegen.java.cc.types.Round
import com.digitalasset.canton.BaseTest
import java.math.BigDecimal
import java.util.Optional
import org.scalatest.wordspec.AnyWordSpec

class CNNodeUtilTest extends AnyWordSpec with BaseTest {
  val coin = new Coin(
    "svc",
    "svc",
    new ExpiringAmount(
      new BigDecimal(1.0).setScale(10),
      new Round(0L),
      new RatePerRound(new BigDecimal(0.5).setScale(10)),
    ),
    Optional.empty(),
  )
  "compute holding fees" in {
    CNNodeUtil.holdingFee(coin, 0L) shouldBe new BigDecimal(0.0).setScale(10)
    CNNodeUtil.holdingFee(coin, 1L) shouldBe new BigDecimal(0.5).setScale(10)
    CNNodeUtil.holdingFee(coin, 2L) shouldBe new BigDecimal(1.0).setScale(10)
    // Capped at initial amount
    CNNodeUtil.holdingFee(coin, 3L) shouldBe new BigDecimal(1.0).setScale(10)
  }
  "compute current amount" in {
    CNNodeUtil.currentAmount(coin, 0L) shouldBe new BigDecimal(1.0).setScale(10)
    CNNodeUtil.currentAmount(coin, 1L) shouldBe new BigDecimal(0.5).setScale(10)
    CNNodeUtil.currentAmount(coin, 2L) shouldBe new BigDecimal(0.0).setScale(10)
    // Capped at 0
    CNNodeUtil.currentAmount(coin, 3L) shouldBe new BigDecimal(0.0).setScale(10)
  }
}
