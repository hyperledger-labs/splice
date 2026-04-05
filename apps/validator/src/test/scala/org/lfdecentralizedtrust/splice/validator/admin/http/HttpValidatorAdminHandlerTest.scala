package org.lfdecentralizedtrust.splice.validator.admin.http

import org.lfdecentralizedtrust.splice.store.StoreTestBase
import org.lfdecentralizedtrust.splice.util.HoldingsSummary

import java.time.Instant

class HttpValidatorAdminHandlerTest extends StoreTestBase {

  "HttpValidatorAdminHandler" should {

    "summarize external-party balances as of the latest open round" in {
      val now = Instant.parse("2026-01-01T00:10:00Z")
      val openRounds = Seq(
        openMiningRound(dsoParty, 1L, 1.0, opensAt = now.minusSeconds(600)),
        openMiningRound(dsoParty, 2L, 1.0, opensAt = now.minusSeconds(60)),
      )
      val user = userParty(1)
      val amulet = this.amulet(user, BigDecimal(100), createdAtRound = 2L, ratePerRound = 0.5)

      val negativeSummary = HoldingsSummary.Empty.addAmulet(amulet.payload, 1L)
      negativeSummary.accumulatedHoldingFeesTotal.signum shouldBe -1

      val balance = HttpValidatorAdminHandler.buildExternalPartyBalanceResponse(
        user.toProtoPrimitive,
        HttpValidatorAdminHandler.latestExternalPartyBalanceRound(now, openRounds.map(_.payload)),
        Seq(amulet.payload),
        Seq.empty,
      )

      balance.totalUnlockedCoin shouldBe "100.0000000000"
      balance.computedAsOfRound shouldBe 2L
      BigDecimal(balance.accumulatedHoldingFeesTotal) shouldBe BigDecimal(0)
    }
  }
}
