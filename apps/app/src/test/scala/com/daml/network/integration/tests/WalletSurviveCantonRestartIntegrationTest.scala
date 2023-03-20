package com.daml.network.integration.tests

import better.files.*
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{CantonProcessTestUtil, WalletTestUtil}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.scalatest.Succeeded

import scala.util.Using
import scala.concurrent.duration.*

class WalletSurviveCantonRestartIntegrationTest
    extends CoinIntegrationTest
    with CantonProcessTestUtil
    with WalletTestUtil {

  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources" / "include"
  val validatorPath: File = examplesPath / "validator"

  val cantonArgs: Seq[String] = Seq(
    "-c",
    (validatorPath / "validator-participant.conf").toString,
    "-c",
    (testResourcesPath / "self-hosted-participant-postgres-storage.conf").toString,
    "-c",
    (testResourcesPath / "storage-postgres.conf").toString,
    "-C",
    "canton.participants.validatorParticipant.ledger-api.port=7201",
    "-C",
    "canton.participants.validatorParticipant.admin-api.port=7202",
  )

  override protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq(
    ("ParticipantLedgerApi", 7201),
    ("ParticipantAdminApi", 7202),
  )

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] = {
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) =>
        CNNodeConfigTransforms.bumpSelfHostedParticipantPortsBy(2000)(conf)
      )
      .withPreSetup(_ => ())
      .withAllocatedSvcAndSvUsers()
      .withManualStart
  }

  "Wallet" should {
    "survive canton restarts" in { implicit env =>
      initSvc()
      aliceValidator.start()
      aliceWalletBackend.start()

      Using.resource(startCanton(cantonArgs)) { _ =>
        eventuallySucceeds(timeUntilSuccess = 40.seconds) {
          aliceWalletBackend.remoteParticipant.domains
            .connect(DomainAlias.tryCreate("global"), "http://localhost:5008")
        }
        CoinEnvironmentDefinition.withAllocatedValidator(aliceValidator)
        aliceValidator.waitForInitialization()
        aliceWalletBackend.waitForInitialization()

        onboardWalletUser(aliceWallet, aliceValidator)
        aliceWallet.tap(1)
        aliceWallet.list()
      }

      Using.resource(startCanton(cantonArgs)) { _ =>
        eventuallySucceeds(timeUntilSuccess = 40.seconds) {
          aliceWallet.tap(2)
        }
        aliceWallet.list()
      }

      // Make sure we're connecting to the right (now disconnected) participant
      // TODO (#3459): assert something better
      assertThrowsAndLogsCommandFailures(aliceWallet.tap(3), _ => Succeeded)
    }

  }

}
