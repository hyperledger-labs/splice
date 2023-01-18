package com.daml.network.integration.tests.runbook

import java.nio.file.Files
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

import scala.sys.process.Process

/** Runs through runbook but does so while spinning up a local SVC. */
class LocalRunbookIntegrationTest extends CoinIntegrationTest with HasConsoleScriptRunner {
  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val validatorPath: File = examplesPath / "validator"

  val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources"

  val svcParticipantPath: File = testResourcesPath / "canton-participant"
  val svcDomainPath: File = testResourcesPath / "canton-domain"
  val svcAppPath: File = testResourcesPath / "svc-app"
  val scanAppPath: File = testResourcesPath / "scan-app"

  var cantonProcess: Option[Process] = None
  override def provideEnvironment = {
    System.setProperty("DOMAIN_URL", "http://localhost:7008")

    // We merge the bootstrap we need for the SVC & the domain
    // with the bootstrap for the validator so we
    // don't need to start two Canton instances.
    val bootstrapFile: File = Files.createTempFile("canton-bootstrap", "scala")
    val validatorBootstrapContent: String =
      (validatorPath / "validator-participant.canton").contentAsString
    bootstrapFile.overwrite("""
      |val svcUserName = "svc"
      |println("Allocating svc party")
      |val svcParty = svc_participant.parties.enable(svcUserName)
      |println("Creating svc user")
      |svc_participant.ledger_api.users.create(
      |  id = svcUserName,
      |  actAs = Set(svcParty.toLf),
      |  primaryParty = Some(svcParty.toLf),
      |  readAs = Set.empty,
      |  participantAdmin = true,
      |)
      |println("Connecting svc participant to domain")
      |svc_participant.domains.connect_local(svc_domain)
      |""".stripMargin)
    bootstrapFile.append(validatorBootstrapContent)

    val builder = Process(
      command = Seq(
        "canton",
        "daemon",
        "-c",
        (validatorPath / "validator-participant.conf").toString,
        "-c",
        (svcParticipantPath / "canton.conf").toString,
        "-c",
        (svcDomainPath / "canton.conf").toString,
        "-C",
        "canton.participants.validatorParticipant.ledger-api.port=7001",
        "-C",
        "canton.participants.validatorParticipant.admin-api.port=7002",
        "--bootstrap",
        bootstrapFile.toString,
        "-DDOMAIN_URL=http://localhost:7008",
      )
    )
    cantonProcess = Some(builder.run())
    super.provideEnvironment
  }

  override def testFinished(env: CoinTestConsoleEnvironment): Unit = {
    super.testFinished(env)
    cantonProcess.foreach { p =>
      p.destroy()
      p.exitValue()
    }
  }

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        validatorPath / "validator.conf",
        svcAppPath / "coin.conf",
        scanAppPath / "coin.conf",
        testResourcesPath / "localrunbook-overrides.conf",
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
      .addConfigTransforms((_, conf) =>
        CoinConfigTransforms.useSelfSignedTokensForWalletValidatorApiAuth("test")(conf)
      )
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      .withThisSetup(env => {
        import env._
        env.appsHostedBySvc.local.start()(env)
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
    runScript(validatorPath / "validator.canton")(env.environment)
    runScript(validatorPath / "tap-transfer-demo.canton")(env.environment)
  }
}
