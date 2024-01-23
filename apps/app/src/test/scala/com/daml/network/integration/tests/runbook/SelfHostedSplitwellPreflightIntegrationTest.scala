package com.daml.network.integration.tests.runbook

import better.files.*
import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{CommonCNNodeAppInstanceReferences, ProcessTestUtil, SplitwellTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

/** Preflight test that spins up a new validator following our runbook.
  */
class SelfHostedSplitwellPreflightIntegrationTest
    extends CNNodeIntegrationTest
    with HasConsoleScriptRunner
    with ProcessTestUtil
    with PreflightIntegrationTestUtil
    with SplitwellTestUtil
    with CommonCNNodeAppInstanceReferences {

  private val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  private val validatorPath: File = examplesPath / "validator"
  private val splitwellPath: File = examplesPath / "splitwell"

  override protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq(
    // Validator participant
    ("ParticipantLedgerApi", 7001),
    ("ParticipantAdminApi", 7002),
    // Splitwell validator paeticipant
    ("ParticipantLedgerApi", 7101),
    ("ParticipantAdminApi", 7102),
  )

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        validatorPath / "validator.conf",
        validatorPath / "validator-onboarding-nosecret.conf",
        splitwellPath / "splitwell-validator.conf",
        splitwellPath / "splitwell.conf",
        splitwellPath / "splitwell-validator-onboarding-nosecret.conf",
        splitwellPath / "splitwell-users.conf",
      )
      // clearing default config transforms because they have settings
      // we don't want such as adjusting daml names or triggering automation every second
      .clearConfigTransforms()
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.withPausedSvOffboardingMediatorTrigger()(config)
      )
      .addConfigTransforms((_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(2000)(conf))
      .addConfigTransforms((_, conf) =>
        CNNodeConfigTransforms.bumpRemoteSplitwellPortsBy(2000)(conf)
      )
      // Obtain a fresh onboarding secret from a SV because this is what we want runbook users to do.
      .addConfigTransforms((_, conf) => insertValidatorOnboardingSecret(conf))
      // Replace the path to the splitwell dar file.
      .addConfigTransforms((_, conf) => replaceDarFilePath(conf))
      .withManualStart

  "run through runbook with self-hosted splitwell" in { implicit env =>
    // Start Canton as a separate process. We do that here rather than in the env setup
    // because it is only needed for this one test.
    withCanton(
      Seq(
        validatorPath / "validator-participant.conf",
        splitwellPath / "splitwell-participant.conf",
      ),
      Seq(
        "canton.participants-x.validatorParticipant.ledger-api.port=7001",
        "canton.participants-x.validatorParticipant.admin-api.port=7002",
        "canton.participants-x.splitwellParticipant.ledger-api.port=7101",
        "canton.participants-x.splitwellParticipant.admin-api.port=7102",
      ),
      "self-hosted-splitwell",
    ) {
      runScript(validatorPath / "validator.sc")(env.environment)

      v("validatorApp").participantClient.dars
        .upload("./daml/splitwell/.daml/dist/splitwell-0.1.0.dar")

      val aliceUserName = aliceWalletClient.config.ledgerApiUser

      clue("Onboarding Alice") {
        v("validatorApp").onboardUser(aliceUserName)
      }

      actAndCheck(
        "Create spliwell install requests",
        aliceSplitwellClient.createInstallRequests(),
      )(
        "Wait for splitwell installs",
        requests => {
          aliceSplitwellClient.listSplitwellInstalls().keys shouldBe requests.keys
        },
      )

      actAndCheck("Request groups", aliceSplitwellClient.requestGroup("mygroup"))(
        "Wait for groups",
        _ => {
          aliceSplitwellClient.listGroups() should have size 1
        },
      )

      // Stop nodes before Canton is shutdown
      env.coinNodes.local.foreach(_.stop())
    }
  }

  private def replaceDarFilePath(conf: CNNodeConfig): CNNodeConfig = {

    conf.validatorApps should have size 2

    CNNodeConfigTransforms.updateAllValidatorConfigs_(vc => {
      vc.focus(_.appInstances)
        .modify(instances =>
          instances.updatedWith("splitwell")(instance =>
            instance.map(
              _.focus(_.dars).modify(paths =>
                paths.map(_.toString.replace("dars/", "./daml/splitwell/.daml/dist/").toFile.path)
              )
            )
          )
        )
    })(conf)
  }
}
