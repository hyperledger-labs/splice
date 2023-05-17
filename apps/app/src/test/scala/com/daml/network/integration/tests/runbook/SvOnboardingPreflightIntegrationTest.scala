package com.daml.network.integration.tests.runbook

import better.files.*
import com.daml.network.LiveDevNetTest
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestConsoleEnvironment,
  CNNodeIntegrationTestWithSharedEnvironment,
}
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient.SvOnboardingStatus
import com.daml.network.util.ProcessTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

import scala.util.Using

/** Preflight test that onboards a new SV following our runbook.
  */
class SvOnboardingPreflightIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasConsoleScriptRunner
    with ProcessTestUtil
    with PreflightIntegrationTestUtil {

  private val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources"
  private val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  private val svPath: File = examplesPath / "sv"

  override protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq(
    ("ParticipantLedgerApi", 6001),
    ("ParticipantAdminApi", 6002),
  )

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        svPath / "sv.conf",
        svPath / "sv-onboarding.conf",
        svPath / "validator-onboarding-nosecret.conf",
        testResourcesPath / "preflight-topology.conf",
      )
      // clearing default config transforms because they have settings
      // we don't want such as adjusting daml names or triggering automation every second
      .clearConfigTransforms()
      .addConfigTransforms((_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(1000)(conf))
      // Disable autostart, because our apps require the participant to be connected to a domain
      // when the app starts. The apps are started manually in `sv-participant.sc` below.
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // Obtain a fresh onboarding secret from a SV because this is what we want runbook users to do.
      .addConfigTransforms((_, conf) => insertValidatorOnboardingSecret(conf))

  "run through sv onboarding runbook" taggedAs LiveDevNetTest in { implicit env =>
    // TODO(M3-53) Consider running this test more than once per deployment once we can offboard SVs
    // and/or remove the SVC party from their participants.
    sv1Client.getSvOnboardingStatus("svTest") match {
      case _: SvOnboardingStatus.Completed =>
        println(
          "Ignoring SV onboarding preflight check as we already ran it once in this cluster."
        )
      case _ => {
        // Start Canton as a separate process. We do that here rather than in the env setup
        // because it is only needed for this one test.
        val cantonArgs = Seq(
          "-c",
          (svPath / "sv-participant.conf").toString,
          "-c",
          (testResourcesPath / "include" / "self-hosted-sv-participant-postgres-storage.conf").toString,
          "-c",
          (testResourcesPath / "include" / "storage-postgres.conf").toString,
          "-C",
          "canton.participants.svParticipant.ledger-api.port=6001",
          "-C",
          "canton.participants.svParticipant.admin-api.port=6002",
          "--bootstrap",
          (svPath / "sv-participant.sc").toString,
        )
        Using.resource(startCanton(cantonArgs, "self-hosted-sv")) { _ =>
          runScript(svPath / "sv.sc")(env.environment)

          // Stop nodes before Canton is shutdown
          env.coinNodes.local.foreach(_.stop())
        }
      }
    }
  }
}
