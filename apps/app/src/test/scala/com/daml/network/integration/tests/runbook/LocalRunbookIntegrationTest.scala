package com.daml.network.integration.tests.runbook

import better.files.{File, _}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.integration.{
  CoinConfigTransform,
  CoinConfigTransforms,
  CoinEnvironmentDefinition,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens._

/** Runs through runbook but does so while spinning up a local SVC. */
class LocalRunbookIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with HasConsoleScriptRunner {
  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val validatorPath: File = examplesPath / "validator"
  val svcParticipantPath = "canton-participant"
  val svcDomainPath = "canton-domain"
  val svcAppPath = "svc-app"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        validatorPath / "validator.conf",
        validatorPath / "validator-participant.conf",
        svcParticipantPath / "svc-participant.conf",
        svcDomainPath / "svc-domain.conf",
        svcAppPath / "svc.conf",
      )
      .clearConfigTransforms()
      .addConfigTransforms((_, conf) => CoinConfigTransforms.bumpCantonPortsBy1000(conf))
      .addConfigTransform((_, conf) => remoteScanAddressToLocalhost(conf))
      .addConfigTransform((_, conf) => remoteParticipantAddressToLocalhost(conf))

  private def remoteScanAddressToLocalhost: CoinConfigTransform = {
    CoinConfigTransforms.updateAllValidatorConfigs_(
      _.focus(_.remoteScan.adminApi.address).replace("localhost")
    ) compose CoinConfigTransforms.updateAllWalletAppConfigs_(
      _.focus(_.remoteScan.adminApi.address).replace("localhost")
    )
  }

  private def remoteParticipantAddressToLocalhost: CoinConfigTransform = {
    CoinConfigTransforms.updateSvcConfig(
      _.focus(_.remoteParticipant.adminApi.address)
        .replace("localhost")
        .focus(_.remoteParticipant.ledgerApi.address)
        .replace("localhost")
    ) compose CoinConfigTransforms.updateCcScanConfig(
      _.focus(_.remoteParticipant.adminApi.address)
        .replace("localhost")
        .focus(_.remoteParticipant.ledgerApi.address)
        .replace("localhost")
    )
  }

  "run through runbook against local SVC" in { implicit env =>
    System.setProperty("DOMAIN_URL", "http://localhost:5008")

    runScript(svcParticipantPath / "bootstrap.scala")(env.environment)
    runScript(svcAppPath / "svc.canton")(env.environment)
    runScript(validatorPath / "validator-participant.canton")(env.environment)
    runScript(validatorPath / "tap-transfer-demo.canton")(env.environment)
  }
}
