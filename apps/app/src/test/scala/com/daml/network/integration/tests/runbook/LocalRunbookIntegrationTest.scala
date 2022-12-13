package com.daml.network.integration.tests.runbook

import better.files.{File, *}
import com.daml.network.config.{CoinConfigTransform, CoinConfigTransforms}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

import scala.util.Try

/** Runs through runbook but does so while spinning up a local SVC. */
class LocalRunbookIntegrationTest extends CoinIntegrationTest with HasConsoleScriptRunner {
  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val clusterImagesPath: File = "cluster" / "images"
  val validatorPath: File = examplesPath / "validator"
  val svcParticipantPath: File = clusterImagesPath / "canton-participant"
  val svcDomainPath: File = clusterImagesPath / "canton-domain"
  val svcAppPath: File = clusterImagesPath / "svc-app"
  val scanAppPath: File = clusterImagesPath / "scan-app"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        validatorPath / "validator.conf",
        validatorPath / "validator-participant.conf",
        svcParticipantPath / "coin.conf",
        svcDomainPath / "coin.conf",
        svcAppPath / "coin.conf",
        scanAppPath / "coin.conf",
      )
      .clearConfigTransforms()
      // Bump ports by 1000 to avoid collisions with the Canton instance started outside of our tests.
      .addConfigTransforms((_, conf) => CoinConfigTransforms.bumpCantonPortsBy(2000)(conf))
      // Our SVC participant is instance 0 usually. However in our runbook
      // our users are not exposed to that so we also use 0 for their participant. This
      // rewrites the SVC ports by an extra 1000 to avoid collisions.
      .addConfigTransforms((_, conf) => CoinConfigTransforms.bumpSvcParticipantPortsBy(2000)(conf))
      .addConfigTransform((_, conf) => remoteScanAddressToLocalhost(conf))
      .addConfigTransform((_, conf) => remoteParticipantAddressToLocalhost(conf))
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      .withThisSetup(env => {
        import env._
        // This section starts the core of the Canton Network (i.e., it does not include self-hosted
        // participants and other apps that are part of a self-hosted validator).
        Seq(
          domains.local,
          Seq(p("svc_participant")),
          env.appsHostedBySvc.local,
        ).flatten.start()(env)
        // ... (2) create the SVC user...
        val svcUserName = "svc"
        val svcParty = p("svc_participant").parties.enable(svcUserName)
        p("svc_participant").ledger_api.users.create(
          id = svcUserName,
          actAs = Set(svcParty.toLf),
          primaryParty = Some(svcParty.toLf),
          readAs = Set.empty,
          participantAdmin = true,
        )
        // ... (3) connect the SVC participant to the SVC domain...
        p("svc_participant").domains.connect_local(d("svc_domain"))
        // ... (4) Self-hosted validator is started in the test.
      })

  private def remoteScanAddressToLocalhost: CoinConfigTransform = {
    CoinConfigTransforms.updateAllValidatorConfigs_(
      _.focus(_.remoteScan.adminApi.address).replace("localhost")
    ) compose CoinConfigTransforms.updateAllWalletAppBackendConfigs_(
      _.focus(_.remoteScan.adminApi.address).replace("localhost")
    )
  }

  private def remoteParticipantAddressToLocalhost: CoinConfigTransform = {
    CoinConfigTransforms.updateSvcAppConfig(
      _.focus(_.remoteParticipant.adminApi.address)
        .replace("localhost")
        .focus(_.remoteParticipant.ledgerApi.clientConfig.address)
        .replace("localhost")
    ) compose CoinConfigTransforms.updateScanAppConfig(
      _.focus(_.remoteParticipant.adminApi.address)
        .replace("localhost")
        .focus(_.remoteParticipant.ledgerApi.clientConfig.address)
        .replace("localhost")
    )
  }

  // TODO(#1983)
  "run through runbook against local SVC" in { implicit env =>
    val propName = "DOMAIN_URL"
    val prevProperty = System.getProperty(propName)
    val result = Try {
      System.setProperty(propName, "http://localhost:7008")

      runScript(validatorPath / "validator-participant.canton")(env.environment)
      runScript(validatorPath / "validator.canton")(env.environment)
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
