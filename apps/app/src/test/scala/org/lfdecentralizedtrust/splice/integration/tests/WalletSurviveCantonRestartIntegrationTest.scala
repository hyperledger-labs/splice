package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton, WalletTestUtil}
import org.slf4j.event.Level

import scala.concurrent.duration.*

class WalletSurviveCantonRestartIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with StandaloneCanton
    with WalletTestUtil {

  override def dbsSuffix = "wallet_survive_canton_restart"
  val dbName = s"participant_alice_validator_${dbsSuffix}"
  override def usesDbs = Seq(dbName) ++ super.usesDbs

  // Can sometimes be unhappy when doing funky `withCanton` things; disabling them for simplicity
  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1SvWithLocalValidator(this.getClass.getSimpleName)
      .withSequencerConnectionsFromScanDisabled()
      .addConfigTransforms((_, conf) =>
        ConfigTransforms.updateAllValidatorConfigs { case (name, config) =>
          if (name == "aliceValidatorLocalBackend") {
            import monocle.macros.syntax.lens.*
            config.focus(_.parameters.enabledFeatures.newSequencerConnectionPool).replace(true)
          } else {
            config
          }
        }(conf)
      )
  }

  "Wallet" should {
    "survive Canton restarts" in { implicit env =>
      initDso()
      clue("First run of Canton participant") {
        withCanton(
          Seq(
            testResourcesPath / "standalone-participant-extra.conf"
          ),
          Seq.empty,
          "wallet-survive-canton-restarts-1",
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> dbName,
        ) {
          aliceValidatorLocalBackend.startSync()
          actAndCheck(
            "Onboard wallet user",
            onboardWalletUser(aliceWalletClient, aliceValidatorLocalBackend),
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
        withCanton(
          Seq(
            testResourcesPath / "standalone-participant-extra.conf"
          ),
          Seq.empty,
          "wallet-survive-canton-restarts-2",
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> dbName,
        ) {
          clue("We can tap and list after Canton restart and domain reconnection") {
            loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
              {
                // Due to the circuit breaker kicking in, this might take a bit longer to succeed after some failures due to the disconnect
                // Disable suppressErrors to avoid nested loggerFactory calls
                eventuallySucceeds(2.minutes, suppressErrors = false) {
                  aliceWalletClient.tap(2)
                }
                aliceWalletClient.list()
              },
              logEntries => {
                forAtMost(1, logEntries) { logEntry =>
                  logEntry.message should include(
                    "Circuit breaker treasury tripped after 20 failures"
                  )
                }
              },
            )
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
