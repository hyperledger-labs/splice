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
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.console.CommandFailure

import scala.util.Using
import scala.concurrent.duration.*

class XNodeWalletSurviveCantonRestartIntegrationTest
    extends CNNodeIntegrationTest
    with ProcessTestUtil
    with WalletTestUtil {

  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources" / "include"
  val validatorPath: File = examplesPath / "validator"

  val cantonArgs: Seq[String] = Seq(
    "-c",
    (validatorPath / "validator-participant-x.conf").toString,
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
      .simpleTopologyXCentralizedDomain(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .addConfigTransforms((_, conf) =>
        CNNodeConfigTransforms.bumpSelfHostedParticipantPortsBy(2000)(conf)
      )
      .withPreSetup(implicit env =>
        CNNodeEnvironmentDefinition.withAllocatedValidator(sv1Validator)
      )
      .withAllocatedSvcAndSvUsers()
      .withManualStart
  }

  "Wallet" should {
    "survive Canton restarts" in { implicit env =>
      initSvc()
      aliceValidator.start()
      clue("First run of Canton participant") {
        Using.resource(startCanton(cantonArgs, "wallet-survives-canton-restarts-1")) { _ =>
          clue("Connect new participant to global domain") {
            eventuallySucceeds(timeUntilSuccess = 40.seconds) {
              aliceValidator.participantClient.domains
                .connect(DomainAlias.tryCreate("global"), "http://localhost:5008")
            }
          }
          clue("Wait for validator initialization") {
            CNNodeEnvironmentDefinition.withAllocatedValidator(aliceValidator)
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
          clue("Connect restarted participant to global domain again") {
            eventuallySucceeds(timeUntilSuccess = 40.seconds) {
              aliceValidator.participantClient.domains
                .connect(DomainAlias.tryCreate("global"), "http://localhost:5008")
            }
          }
          clue("We can tap and list after Canton restart and domain reconnection") {
            aliceWallet.tap(2)
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
