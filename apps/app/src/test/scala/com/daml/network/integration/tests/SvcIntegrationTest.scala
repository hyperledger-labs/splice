package com.daml.network.integration.tests
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.codegen.CC.Coin._
import com.daml.network.codegen.CC.Round._

class SvcIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  "restart cleanly" in { implicit env =>
    // TODO(M1-92): share tests for common properties of CoinApps, like restartabilty
    svc.stop()
    svc.startSync()
  }

  "round management" in { implicit env =>
    val coinPrice: BigDecimal = 23.0

    // Sync with background automation that onboards validator.
    eventually()({
      val requests = svc.remoteParticipant.ledger_api.acs
        .filter(svcParty, OpenMiningRound)
      requests should have length 5
    })

    val closingRounds = svc.startClosingRound(0)
    closingRounds should have size 5
    svc.remoteParticipant.ledger_api.acs
      .filter(svcParty, ClosingMiningRound)
      .map(_.contractId) should contain theSameElementsAs closingRounds.values
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, OpenMiningRound) shouldBe empty

    val issuingRoundResponse = svc.startIssuingRound(0)
    issuingRoundResponse.validatorRounds should have size 5
    svc.remoteParticipant.ledger_api.acs
      .filter(svcParty, IssuingMiningRound)
      .map(
        _.contractId
      ) should contain theSameElementsAs issuingRoundResponse.validatorRounds.values
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, ClosingMiningRound) shouldBe empty

    val closedRounds = svc.closeRound(0)
    closedRounds should have size 5
    svc.remoteParticipant.ledger_api.acs
      .filter(svcParty, ClosedMiningRound)
      .map(_.contractId) should contain theSameElementsAs closedRounds.values
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, IssuingMiningRound) shouldBe empty

    svc.archiveRound(0)
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, ClosedMiningRound) shouldBe empty

    remoteSvc.openRound(coinPrice)
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, OpenMiningRound) should have length 5

  }

  "total burn calculation" in { implicit env =>
    // 3 app rewards & 3 validator rewards, 2 of each for round 0 and one for round 1
    // to check we sum up but only for the right round.
    val rewards = Seq(
      AppReward(
        svc = svcParty.toPrim,
        provider = svcParty.toPrim,
        quantity = 1.0,
        round = Round(0),
      ),
      AppReward(
        svc = svcParty.toPrim,
        provider = svcParty.toPrim,
        quantity = 2.0,
        round = Round(0),
      ),
      AppReward(
        svc = svcParty.toPrim,
        provider = svcParty.toPrim,
        quantity = 5.0,
        round = Round(1),
      ),
      ValidatorReward(
        svc = svcParty.toPrim,
        user = svcParty.toPrim,
        quantity = 3.0,
        round = Round(0),
      ),
      ValidatorReward(
        svc = svcParty.toPrim,
        user = svcParty.toPrim,
        quantity = 4.0,
        round = Round(0),
      ),
      ValidatorReward(
        svc = svcParty.toPrim,
        user = svcParty.toPrim,
        quantity = 5.0,
        round = Round(1),
      ),
    )
    // Create a bunch of rewards directly
    svc.remoteParticipant.ledger_api.commands.submit(
      actAs = Seq(svcParty),
      optTimeout = None,
      commands = rewards.map(_.create.command),
    )
    svc.startClosingRound(0)
    val result = svc.startIssuingRound(0)
    result.totalBurnQuantity shouldBe BigDecimal(1 + 2 + 3 + 4)
  }
}
