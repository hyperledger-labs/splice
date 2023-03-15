package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil, CoinUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import scala.jdk.CollectionConverters.*

class ScanTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)

  "report correct reference data" in { implicit env =>
    scan.getLatestOpenMiningRound(getLedgerTime).payload.round.number shouldBe 1

    advanceRoundsByOneTick
    scan.getLatestOpenMiningRound(getLedgerTime).payload.round.number shouldBe 2
  }

  "return correct coin configs" in { implicit env =>
    // TODO(#2930) test also with changing coin prices.
    clue("Get config for the first three rounds, which should be created on bootstrap") {
      eventuallySucceeds() {
        Range(0, 3).foreach(round => {
          val cfg = scan.getCoinConfigForRound(round.toLong)
          cfg.coinCreateFee.bigDecimal.setScale(10) should be(
            CoinUtil.defaultCreateFee.fee.setScale(10)
          )
          cfg.holdingFee.bigDecimal.setScale(10) should be(
            CoinUtil.defaultHoldingFee.rate.setScale(10)
          )
          cfg.lockHolderFee.bigDecimal.setScale(10) should be(
            CoinUtil.defaultLockHolderFee.fee.setScale(10)
          )
          cfg.transferFee.initial.bigDecimal.setScale(10) should be(
            CoinUtil.defaultTransferFee.initialRate.setScale(10)
          )
          cfg.transferFee.steps shouldBe (
            CoinUtil.defaultTransferFee.steps.asScala.toSeq.map(step =>
              HttpScanAppClient.RateStep(step._1, step._2)
            )
          )
        })
      }
    }

    clue("Try to get config for round 3 which does not yet exist") {
      assertThrowsAndLogsCommandFailures(
        scan.getCoinConfigForRound(3),
        _.errorMessage should include("Round 3 not found"),
      )
    }

    val newHoldingFee = 0.1
    clue("schedule a config change, and advance time for it to take effect") {
      val configSchedule =
        createConfigSchedule(
          (defaultTickDuration.duration, mkCoinConfig(holdingFee = newHoldingFee))
        )
      svcClient.setConfigSchedule(configSchedule)
      advanceRoundsByOneTick
    }
    clue("Round 3 should now be open, and have the new configuration") {
      eventuallySucceeds() {
        scan.getCoinConfigForRound(3).holdingFee should be(newHoldingFee)
      }
    }
  }
}
