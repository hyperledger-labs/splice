package com.daml.network.integration.tests

import better.files.*
import com.daml.network.config.ConfigTransforms
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.util.{ProcessTestUtil, WalletTestUtil}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.duration.*

class WalletSurviveCantonRestartIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with WalletTestUtil {

  val includeTestResourcesPath: File = testResourcesPath / "include"

  val cantonArgs: Seq[File] = Seq(
    includeTestResourcesPath / "validator-participant.conf",
    includeTestResourcesPath / "self-hosted-validator-disable-json-api.conf",
    includeTestResourcesPath / "self-hosted-validator-participant-postgres-storage.conf",
    includeTestResourcesPath / "storage-postgres.conf",
  )
  val cantonExtraConfig: Seq[String] =
    Seq(
      "canton.participants.validatorParticipant.ledger-api.port=7501",
      "canton.participants.validatorParticipant.admin-api.port=7502",
    )

  override protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq(
    ("ParticipantLedgerApi", 7501),
    ("ParticipantAdminApi", 7502),
  )

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .addConfigTransforms((_, conf) =>
        ConfigTransforms.bumpSelfHostedParticipantPortsBy(2000)(conf)
      )
      // Do not allocate validator users here, as we deal with all of them manually
      .withAllocatedUsers(extraIgnoredValidatorPrefixes = Seq(""))
      .withManualStart
      // TODO(#8300) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()
  }

  "Wallet" should {
    "survive Canton restarts" in { implicit env =>
      initDso()
      aliceValidatorBackend.start()
      clue("First run of Canton participant") {
        withCanton(cantonArgs, cantonExtraConfig, "wallet-survives-canton-restarts-1") {
          clue("Wait for validator initialization") {
            // Need to wait for the participant node to startup for the user allocation to go through
            eventuallySucceeds(timeUntilSuccess = 40.seconds) {
              EnvironmentDefinition.withAllocatedValidatorUser(aliceValidatorBackend)
            }
            aliceValidatorBackend.waitForInitialization()
          }
          actAndCheck(
            "Onboard wallet user",
            onboardWalletUser(aliceWalletClient, aliceValidatorBackend),
          )(
            "We can tap and list",
            _ => {
              aliceWalletClient.tap(1)
              aliceWalletClient.list()
            },
          )
        }
      }
      clue("Second run of Canton participant") {
        withCanton(cantonArgs, cantonExtraConfig, "wallet-survives-canton-restarts-2") {
          clue("We can tap and list after Canton restart and domain reconnection") {
            eventuallySucceeds() {
              aliceWalletClient.tap(2)
            }
            aliceWalletClient.list()
          }
        }
      }
      clue("Check we're connecting to the correct (now disconnected) participant") {
        // This tap will fail in one of two ways:
        // 1. If this tap hits the first batch in the wallet after the disconnect, it will fail immediately with an IO exception.
        // 2. Alternatively, sometimes the wallet tries to merge the amulets before we kill canton, but we then kill it, which causes the
        //    treasury service to be blocked waiting for ingestion of the merge. In this case, this tap will just get queued and eventually
        //    time out at the pekko-http server.
        // Both failures lead to a CommandFailure exception here.
        // Timeouts log as WARN, so those need to be suppressed too.
        loggerFactory.suppressWarningsAndErrors(
          an[CommandFailure] should be thrownBy aliceWalletClient.tap(3)
        )
      }
    }

  }

}
