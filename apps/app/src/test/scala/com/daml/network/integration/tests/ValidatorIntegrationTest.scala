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
import com.daml.network.codegen.CC
import monocle.macros.syntax.lens._

class ValidatorIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // We manually start apps so we disable the default setup
      // that blocks on all apps being initialized.
      .withSetup(setup = _ => ())

  "initialize svc and validator apps" in { implicit env =>
    svc.start()
    scan.start()
    svc.waitForInitialization()
    // check that there is exactly one CoinRule and OpenMiningRound
    val coinRules = svc.remoteParticipant.ledger_api.acs
      .of_party(svcParty, filterTemplates = Seq(CC.CoinRules.CoinRules.id))
    coinRules should have length 1

    val openRounds = svc.remoteParticipant.ledger_api.acs
      .of_party(svcParty, filterTemplates = Seq(CC.Round.OpenMiningRound.id))
    openRounds should have length 1

    // Start Alice’s validator
    aliceValidator.start()
    aliceValidator.waitForInitialization()

    // check that no coin rules request is outstanding
    eventually()(
      svc.remoteParticipant.ledger_api.acs
        .filter(svcParty, CC.CoinRules.CoinRulesRequest)
        shouldBe empty
    )

    // check that alice's validator can see the coinrules
    val aliceValidatorParty = aliceValidator.getValidatorPartyId()
    aliceValidator.remoteParticipant.ledger_api.acs
      .await(aliceValidatorParty, CC.CoinRules.CoinRules)

    // onboard end user
    aliceValidator.onboardUser(aliceRemoteWallet.config.damlUser)
  }

  "onboard users with party hint sanitizer" in { implicit env =>
    // Start nodes
    svc.start()
    scan.start()
    aliceValidator.start()
    aliceValidator.waitForInitialization()

    // Make uniqueness of the user ID more probable when running the test multiple times in a row
    val randomId = (new scala.util.Random).nextInt(10000)

    val partyIdFromBadUserId = aliceValidator.onboardUser(s"test@example!#+~-user|123|${randomId}")
    partyIdFromBadUserId.toString
      .split("::")
      .head should fullyMatch regex (s"test_example____-user_123_${randomId}-.*")

    val partyIdFromGoodUserId = aliceValidator.onboardUser(s"other-_us:er-${randomId}")
    partyIdFromGoodUserId.toString
      .split("::")
      .head should fullyMatch regex (s"other-_us:er-${randomId}")
  }
}
