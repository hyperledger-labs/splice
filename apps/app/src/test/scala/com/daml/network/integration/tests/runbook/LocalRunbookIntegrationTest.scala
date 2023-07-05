package com.daml.network.integration.tests.runbook

import better.files.{File, *}
import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.config.ExpectedValidatorOnboardingConfig
import com.daml.network.util.ProcessTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

/** Runs through runbook but does so while spinning up a local SVC. */
class LocalRunbookIntegrationTest
    extends CNNodeIntegrationTest
    with HasConsoleScriptRunner
    with ProcessTestUtil {
  import ProcessTestUtil.Process

  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val validatorPath: File = examplesPath / "validator"

  val svNodePath: File = "apps" / "app" / "src" / "test" / "resources" / "local-sv-node"

  val svParticipantPath: File = svNodePath / "canton-participant"
  val svDomainPath: File = svNodePath / "canton-domain"
  val svAppsPath: File = svNodePath / "sv-apps"
  val scanAppPath: File = svNodePath / "scan-apps"

  override protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq(
    ("ParticipantLedgerApi", 7001),
    ("ParticipantAdminApi", 7002),
  )

  var cantonProcess: Option[Process] = None

  // We usually set this through an env var but you cannot easily set env vars in Java so instead we opt for a system property.
  // Note that system properties can only be used in tests at this point.
  System.setProperty("NETWORK_APPS_ADDRESS", "localhost")
  System.setProperty("NETWORK_APPS_ADDRESS_PROTOCOL", "http")

  private def setupAndStartCanton() = {
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
        (svParticipantPath / "canton.conf").toString,
        "-c",
        (svDomainPath / "canton.conf").toString,
        "-C",
        "canton.participants-x.validatorParticipant.ledger-api.port=7001",
        "-C",
        "canton.participants-x.validatorParticipant.admin-api.port=7002",
      ),
      "local-runbook",
    )
    cantonProcess = Some(process)
  }

  override def testFinished(env: CNNodeTestConsoleEnvironment): Unit = {
    super.testFinished(env)
    cantonProcess.foreach { p =>
      p.destroyAndWait()
    }
  }

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        // Config file for the self-hosted validator (original version from the runbook)
        validatorPath / "validator.conf",
        // Config file template for onboarding self-hosted validator (original version from the runbook)
        validatorPath / "validator-onboarding-nosecret.conf",
        // Config files for SVC-hosted apps (modified copies of deployed configs)
        svAppsPath / "app.conf",
        scanAppPath / "app.conf",
      )
      .clearConfigTransforms()
      .addConfigTransforms(
        // In the runbook, the participant of the self-hosted validator uses ports 5xxx.
        // This test starts the participant on ports 7xxx instead, so we need to adjust all remote participant
        // configs of apps started on the self-hosted validator node.
        (_, conf) => CNNodeConfigTransforms.bumpSelfHostedParticipantPortsBy(2000)(conf),
        // The domain ports of the SV are already correct as per its config.
        (_, conf) => CNNodeConfigTransforms.bumpValidatorAppCantonDomainPortsBy(4000)(conf),
        (_, conf) => expectValidatorOnboarding(conf, "validatorsecret"),
        (_, conf) => localValidatorSvSponsorUrl(conf),
        (_, conf) => localValidatorFoundingSvUrl(conf),
        (_, conf) => localScanUrl(conf),
      )
      .withManualStart
      .withThisSetup(env => {
        setupAndStartCanton()
        env.fullSvcApps.local.foreach(_.start())
        env.fullSvcApps.local.foreach(_.waitForInitialization())
      })

  "run through runbook against local SVC" in { implicit env =>
    runScript(validatorPath / "validator.sc")(env.environment)
    runScript(validatorPath / "tap-transfer-demo.sc")(env.environment)
  }

  private def expectValidatorOnboarding(conf: CNNodeConfig, secret: String): CNNodeConfig = {

    // make sure that sv1 expects the secret
    val conf_ = conf
      .focus(_.svApps)
      .modify(_.map { case (svName, svConfig) =>
        if (svName.unwrap == "sv1Local") {
          (
            svName,
            svConfig
              .focus(_.expectedValidatorOnboardings)
              .replace(List(ExpectedValidatorOnboardingConfig(secret))),
          )
        } else {
          (svName, svConfig)
        }
      })
    // insert the secret into the validator onboarding config
    conf.validatorApps should have size (1)
    CNNodeConfigTransforms.updateAllValidatorConfigs_(vc => {
      val oc = vc.onboarding.value
      vc.focus(_.onboarding).replace(Some(oc.copy(secret = secret)))
    })(conf_)
  }

  private def localValidatorSvSponsorUrl(conf: CNNodeConfig): CNNodeConfig = {
    CNNodeConfigTransforms.updateAllValidatorConfigs_(vc =>
      vc.focus(_.onboarding)
        .modify(onboarding =>
          onboarding.map(_.focus(_.svClient.adminApi.url).replace("http://localhost:5014"))
        )
    )(conf)
  }

  private def localValidatorFoundingSvUrl(conf: CNNodeConfig): CNNodeConfig = {
    CNNodeConfigTransforms.updateAllValidatorConfigs_(vc =>
      vc.focus(_.foundingSvClient.adminApi.url).replace("http://localhost:5014")
    )(conf)
  }

  private def localScanUrl(conf: CNNodeConfig): CNNodeConfig = {
    CNNodeConfigTransforms.updateAllValidatorConfigs_(vc =>
      vc.focus(_.scanClient.adminApi.url).replace("http://localhost:5012")
    )(conf)
  }
}
