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
import com.daml.network.util.CantonProcessTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

/** Runs through runbook but does so while spinning up a local SVC. */
class LocalRunbookIntegrationTest
    extends CoinIntegrationTest
    with HasConsoleScriptRunner
    with CantonProcessTestUtil {
  import CantonProcessTestUtil.CantonProcess

  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val validatorPath: File = examplesPath / "validator"

  val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources"

  val svcParticipantPath: File = testResourcesPath / "canton-participant"
  val svcDomainPath: File = testResourcesPath / "canton-domain"
  val svcAppPath: File = testResourcesPath / "svc-app"
  val svAppsPath: File = testResourcesPath / "sv-apps"
  val scanAppPath: File = testResourcesPath / "scan-app"

  var cantonProcess: Option[CantonProcess] = None
  override def provideEnvironment = {
    // We usually set this through an env var but you cannot easily set env vars in Java so instead we opt for a system property.
    // Note that system properties can only be used in tests at this point.
    System.setProperty("NETWORK_APPS_ADDRESS", "http://localhost:5012")
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
      |Seq("sv1", "sv2", "sv3", "sv4").foreach(svUserName => {
      |  println("Allocating " + svUserName + " party")
      |  val svParty = svc_participant.parties.enable(svUserName)
      |  println("Creating " + svUserName + " user")
      |  val foundConsortium = svUserName == "sv1" // we configure sv1 to `found-consortium`
      |  svc_participant.ledger_api.users.create(
      |    id = svUserName,
      |    actAs =
      |      // the SV app will revoke the "act as svcParty" right at the end of its init
      |      if (foundConsortium) Set(svParty.toLf, svcParty.toLf)
      |      else Set(svParty.toLf),
      |    readAs = Set(svcParty.toLf),
      |    primaryParty = Some(svParty.toLf),
      |    participantAdmin = true,
      |  )
      |})
      |println("Connecting svc participant to domain")
      |svc_participant.domains.connect("global", "http://localhost:7008")
      |""".stripMargin)
    bootstrapFile.append(validatorBootstrapContent)

    val process = startCanton(
      Seq(
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
      ),
      ("DOMAIN_URL", "http://localhost:7008"),
    )
    cantonProcess = Some(process)
    super.provideEnvironment
  }

  override def testFinished(env: CoinTestConsoleEnvironment): Unit = {
    super.testFinished(env)
    cantonProcess.foreach { p =>
      p.destroyAndWait()
    }
  }

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        validatorPath / "validator.conf",
        svcAppPath / "app.conf",
        svAppsPath / "app.conf",
        scanAppPath / "app.conf",
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
        env.appsHostedBySvc.local.foreach(_.start())
        env.appsHostedBySvc.local.foreach(_.waitForInitialization())
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
