package com.daml.network.integration.tests.runbook

import java.nio.file.Files
import better.files.{File, *}
import com.daml.network.config.CoinConfigTransforms
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

  val svNodePath: File = "apps" / "app" / "src" / "test" / "resources" / "local-sv-node"

  val svcParticipantPath: File = svNodePath / "canton-participant"
  val svcDomainPath: File = svNodePath / "canton-domain"
  val svcAppPath: File = svNodePath / "svc-app"
  val svAppsPath: File = svNodePath / "sv-apps"
  val scanAppPath: File = svNodePath / "scan-app"

  var cantonProcess: Option[CantonProcess] = None
  override def provideEnvironment = {
    // We usually set this through an env var but you cannot easily set env vars in Java so instead we opt for a system property.
    // Note that system properties can only be used in tests at this point.
    System.setProperty("NETWORK_APPS_ADDRESS", "localhost")
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
      |svc_participant.domains.connect("global", "http://localhost:9008")
      |""".stripMargin)
    bootstrapFile.append(validatorBootstrapContent)

    // Note: the Canton process started here uses ports that do not collide with ports 5xxx used
    // by the persistent Canton instance started by `./start-canton.sh`:
    // - The local SVC node uses ports 9xxx (hardcoded in config files)
    // - The self-hosted validator uses ports 7xxx (set via CLI arguments below)
    // Note: the coin apps started implicitly through `environmentDefinition`, including all SVC-hosted coin apps,
    // still use ports 5xxx. This is fine because we only start coin apps for the duration of tests, and we never
    // run tests in parallel on the same machine.
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
      ("DOMAIN_URL", "http://localhost:9008"),
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
        // Config file for the self-hosted validator (original version from the runbook)
        validatorPath / "validator.conf",
        // Config files for SVC-hosted apps (modified copies of deployed configs)
        svcAppPath / "app.conf",
        svAppsPath / "app.conf",
        scanAppPath / "app.conf",
      )
      .clearConfigTransforms()
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // In the runbook, the participant of the self-hosted validator uses ports 5xxx.
      // This test starts the participant on ports 7xxx instead, so we need to adjust all remote participant
      // configs of apps started on the self-hosted validator node.
      .addConfigTransforms((_, conf) =>
        CoinConfigTransforms.bumpSelfHostedParticipantPortsBy(2000)(conf)
      )
      .withThisSetup(env => {
        env.appsHostedBySvc.local.foreach(_.start())
        env.appsHostedBySvc.local.foreach(_.waitForInitialization())
      })

  // TODO(#1983)
  "run through runbook against local SVC" in { implicit env =>
    runScript(validatorPath / "validator.canton")(env.environment)
    runScript(validatorPath / "tap-transfer-demo.canton")(env.environment)
  }
}
