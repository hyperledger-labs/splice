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

import scala.util.Try

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
      .addConfigTransforms((_, conf) => CoinConfigTransforms.bumpSvcParticipantPortsBy1000(conf))
      .addConfigTransform((_, conf) => remoteScanAddressToLocalhost(conf))
      .addConfigTransform((_, conf) => remoteParticipantAddressToLocalhost(conf))
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      .withSetup(env => {
        import env._
        // It is not possible to start an environment definition that contains Canton components CN components with
        // automatic start in integration tests
        // This is because the SVC app `start` call relies on the Canton participants already being connected to a domain
        // However, when automatic start is enabled it is currently impossible to connect participants to a domain
        // until all other nodes defined the configuration are already started
        // For this reason, we (1) first start the Canton nodes...
        Seq(domains.local, participants.local).flatten.foreach(_.start())
        // ... (2) connect the SVC participant to the SVC domain...
        p("svc_participant").domains.connect_local(d("svc_domain"))
        // ... (3) only then start the rest of the nodes
        nodes.local.foreach(_.start())
      })

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
    val propName = "DOMAIN_URL"
    val prevProperty = System.getProperty(propName)
    val result = Try {
      System.setProperty(propName, "http://localhost:6008")

      runScript(svcParticipantPath / "bootstrap.scala")(env.environment)
      runScript(validatorPath / "validator-participant.canton")(env.environment)
      runScript(validatorPath / "tap-transfer-demo.canton")(env.environment)
    }
    if (prevProperty == null) {
      System.clearProperty(propName)
    } else {
      System.setProperty(propName, prevProperty)
    }
    result.get
  }
}
