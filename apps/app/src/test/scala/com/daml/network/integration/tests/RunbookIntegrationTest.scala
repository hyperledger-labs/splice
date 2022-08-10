package com.daml.network.integration.tests

import better.files._
import com.daml.network.LiveDevNetTest
import com.daml.network.config.CoinConfig
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.{CoinConfigTransforms, CoinEnvironmentDefinition}
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.digitalasset.canton.config.{ClientConfig, CommunityAdminServerConfig}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import com.digitalasset.canton.participant.config.{LedgerApiServerConfig, RemoteParticipantConfig}
import monocle.macros.syntax.lens._

/** Integration test for the runbook. Uses the exact same configuration files and bootstrap scripts as the runbook.
  * This test also doubles as the pre-flight validator test.
  */
class RunbookIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with HasConsoleScriptRunner {
  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val validatorPath: File = examplesPath / "validator"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .fromFiles(validatorPath / "validator.conf", validatorPath / "validator-participant.conf")
      .clearConfigTransforms()
      .addConfigTransforms(portTransform)

  // Bump port by 1000 to avoid collisions with the Canton instance started
  // outside of our tests.
  private def portTransform(c: CommunityAdminServerConfig): CommunityAdminServerConfig =
    c.copy(internalPort = c.internalPort.map(p => p+1000))
  private def portTransform(c: ClientConfig): ClientConfig =
    c.copy(port = c.port+1000)
  private def portTransform(c: LedgerApiServerConfig): LedgerApiServerConfig =
    c.copy(internalPort = c.internalPort.map(p => p+1000))
  private def portTransform(c: RemoteParticipantConfig): RemoteParticipantConfig =
    c.focus(_.adminApi).modify(portTransform).focus(_.ledgerApi).modify(portTransform)

  private def portTransform(c0: CoinConfig): CoinConfig = {
    val c1 = CoinConfigTransforms.updateAllParticipantConfigs_(_.focus(_.adminApi).modify(portTransform).focus(_.ledgerApi).modify(portTransform))(c0)
    val c2 = CoinConfigTransforms.updateAllValidatorConfigs_(_.focus(_.adminApi).modify(portTransform).focus(_.remoteParticipant).modify(portTransform))(c1)
    val c3 = CoinConfigTransforms.updateAllWalletAppConfigs_(_.focus(_.adminApi).modify(portTransform).focus(_.remoteParticipant).modify(portTransform))(c2)
    c3
  }


  // when running locally, this test may fail if the DAR deployed to DevNet differs from the latest one on your branch
  "run through runbook" taggedAs LiveDevNetTest in { implicit env =>
    runScript(validatorPath / "validator-participant.canton")(env.environment)
    runScript(validatorPath / "tap-transfer-demo.canton")(env.environment)
  }
}
