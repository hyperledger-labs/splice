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
import com.daml.network.util.CantonProcessTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

import scala.util.Using
import org.scalatest.Ignore

/** Preflight test that bootstraps a new SV following our runbook.
  */
// TODO(M3-53) Consider running this test on CI once we can offboard SVs / remove the SVC party from their participants.
@Ignore
class SvBootstrappingPreflightIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasConsoleScriptRunner
    with CantonProcessTestUtil {

  private val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources" / "include"
  private val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  private val svPath: File = examplesPath / "sv"

  override protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq(
    ("ParticipantLedgerApi", 6001),
    ("ParticipantAdminApi", 6002),
  )

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .fromFiles(this.getClass.getSimpleName, svPath / "sv.conf", svPath / "sv-bootstrap.conf")
      // clearing default config transforms because they have settings
      // we don't want such as adjusting daml names or triggering automation every second
      .clearConfigTransforms()
      .addConfigTransforms((_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(1000)(conf))
      // Disable autostart, because our apps require the participant to be connected to a domain
      // when the app starts. The apps are started manually in `sv-participant.sc` below.
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))

  "run through sv bootstrapping runbook" taggedAs LiveDevNetTest in { implicit env =>
    // Start Canton as a separate process. We do that here rather than in the env setup
    // because it is only needed for this one test.
    val cantonArgs = Seq(
      "-c",
      (svPath / "sv-participant.conf").toString,
      "-c",
      (testResourcesPath / "self-hosted-sv-participant-postgres-storage.conf").toString,
      "-c",
      (testResourcesPath / "storage-postgres.conf").toString,
      "-C",
      "canton.participants.svParticipant.ledger-api.port=6001",
      "-C",
      "canton.participants.svParticipant.admin-api.port=6002",
      "--bootstrap",
      (svPath / "sv-participant.sc").toString,
    )
    Using.resource(startCanton(cantonArgs)) { _ =>
      runScript(svPath / "sv.sc")(env.environment)

      // Stop nodes before Canton is shutdown
      env.coinNodes.local.foreach(_.stop())
    }
  }
}
