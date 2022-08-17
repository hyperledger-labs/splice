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
import com.digitalasset.network.CC

class ValidatorIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.simpleTopology(this.getClass.getSimpleName)

  "initialize svc and validator apps" in { implicit env =>
    import env._

    // initialize svc
    val svcParty = svc.initialize()

    // check that there is exactly one CoinRule and OpenMiningRound
    val coinRules = svc.remoteParticipant.ledger_api.acs
      .of_party(svcParty, filterTemplates = Seq(CC.CoinRules.CoinRules.id))
    coinRules.length shouldBe 1

    val openRounds = svc.remoteParticipant.ledger_api.acs
      .of_party(svcParty, filterTemplates = Seq(CC.Round.OpenMiningRound.id))
    openRounds.length shouldBe 1

    // initialize alice's validator
    val aliceValidatorParty = aliceValidator.initialize()

    // check that no coin rules request is outstanding
    utils.retry_until_true(
      svc.remoteParticipant.ledger_api.acs
        .filter(svcParty, CC.CoinRules.CoinRulesRequest)
        .isEmpty
    )

    // check that alice's validator can see the coinrules
    aliceValidator.remoteParticipant.ledger_api.acs
      .await(aliceValidatorParty, CC.CoinRules.CoinRules)

    // onboard end user
    aliceValidator.onboardUser(aliceWallet.config.damlUser)
  }

}
