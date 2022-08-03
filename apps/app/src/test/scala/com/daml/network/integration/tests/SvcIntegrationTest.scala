package com.daml.network.integration.tests

import java.util.concurrent.atomic.AtomicReference

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CC.Round._

class SvcIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.simpleTopology.withSetup(env => {
      import env._
      participants.all.foreach(_.domains.connect_local(da))
    })

  "round management" in { implicit env =>
    import env._
    val coinPrice: BigDecimal = 23.0
    val totalBurn: BigDecimal = 42.0
    val svcParty = svc.initialize()
    val validatorParty = validator1.initialize()

    // Sync with background automation that onboards validator.
    utils.retry_until_true({
      val requests = svc.remoteParticipant.ledger_api.acs
        .filter(svcParty, OpenMiningRound)
      requests.length == 2
    })

    val closingRounds = svc.startClosingRound(0)
    closingRounds should have size 2
    svc.remoteParticipant.ledger_api.acs
      .filter(svcParty, ClosingMiningRound)
      .map(_.contractId) should contain theSameElementsAs closingRounds.values
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, OpenMiningRound) shouldBe empty

    val issuingRounds = svc.startIssuingRound(0, totalBurn)
    issuingRounds should have size 2
    svc.remoteParticipant.ledger_api.acs
      .filter(svcParty, IssuingMiningRound)
      .map(_.contractId) should contain theSameElementsAs issuingRounds.values
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, ClosingMiningRound) shouldBe empty

    val closedRounds = svc.closeRound(0)
    closedRounds should have size 2
    svc.remoteParticipant.ledger_api.acs
      .filter(svcParty, ClosedMiningRound)
      .map(_.contractId) should contain theSameElementsAs closedRounds.values
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, IssuingMiningRound) shouldBe empty

    svc.archiveRound(0)
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, ClosedMiningRound) shouldBe empty

    svc.openRound(coinPrice)
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, OpenMiningRound) should have length 2
  }
}
