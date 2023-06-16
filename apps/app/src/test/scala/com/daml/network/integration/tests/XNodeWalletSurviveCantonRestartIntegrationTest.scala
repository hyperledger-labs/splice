package com.daml.network.integration.tests

import better.files.*
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{ProcessTestUtil, WalletTestUtil}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.duration.*
import scala.util.Using

class XNodeWalletSurviveCantonRestartIntegrationTest
    extends CNNodeIntegrationTest
    with ProcessTestUtil
    with WalletTestUtil {

  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources" / "include"
  val validatorPath: File = examplesPath / "validator"

  val cantonArgs: Seq[String] = Seq(
    "-c",
    (validatorPath / "validator-participant.conf").toString,
    "-c",
    (testResourcesPath / "self-hosted-validator-participant-postgres-storage-x.conf").toString,
    "-c",
    (testResourcesPath / "storage-postgres.conf").toString,
    "-C",
    "canton.participants-x.validatorParticipant.ledger-api.port=7201",
    "-C",
    "canton.participants-x.validatorParticipant.admin-api.port=7202",
  )

  override protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq(
    ("ParticipantLedgerApi", 7201),
    ("ParticipantAdminApi", 7202),
  )

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] = {
    CNNodeEnvironmentDefinition
      .simpleTopologyX(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .withPreSetup(_ => ())
      .addConfigTransforms((_, conf) =>
        CNNodeConfigTransforms.bumpSelfHostedParticipantPortsBy(2000)(conf)
      )
      // Do not allocate validator users here, as we deal with all of them manually
      .withAllocatedUsers(extraIgnoredValidatorPrefixes = Seq(""))
      .withManualStart
  }

  "Wallet" should {
    "survive Canton restarts" in { implicit env =>
      initSvc()
      aliceValidator.start()
      clue("First run of Canton participant") {
        Using.resource(startCanton(cantonArgs, "wallet-survives-canton-restarts-1")) { _ =>
          clue("Wait for validator initialization") {
            // Need to wait for the participant node to startup for the user allocation to go through
            eventuallySucceeds(timeUntilSuccess = 40.seconds) {
              CNNodeEnvironmentDefinition.withAllocatedValidatorUser(aliceValidator)
            }
            aliceValidator.waitForInitialization()
          }
          actAndCheck(
            "Onboard wallet user",
            onboardWalletUser(aliceWallet, aliceValidator),
          )(
            "We can tap and list",
            _ => {
              aliceWallet.tap(1)
              aliceWallet.list()
            },
          )
        }
      }
      clue("Second run of Canton participant") {
        Using.resource(startCanton(cantonArgs, "wallet-survives-canton-restarts-2")) { _ =>
          clue("We can tap and list after Canton restart and domain reconnection") {
            eventuallySucceeds() {
              aliceWallet.tap(2)
            }
            aliceWallet.list()
          }
        }
      }
      clue("Check we're connecting to the correct (now disconnected) participant") {
        // This tap will fail in one of two ways:
        // 1. If this tap hits the first batch in the wallet after the disconnect, it will fail immediately with an IO exception.
        // 2. Alternatively, sometimes the wallet tries to merge the coins before we kill canton, but we then kill it, which causes the
        //    treasury service to be blocked waiting for ingestion of the merge. In this case, this tap will just get queued and eventually
        //    time out at the akka-http server.
        // Both failures lead to a CommandFailure exception here.
        // Timeouts log as WARN, so those need to be suppressed too.
        loggerFactory.suppressWarningsAndErrors(
          an[CommandFailure] should be thrownBy aliceWallet.tap(3)
        )
      }
    }

  }

}
