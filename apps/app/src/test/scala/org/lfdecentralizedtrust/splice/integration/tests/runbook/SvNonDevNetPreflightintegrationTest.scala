package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.DataExportTestUtil
import com.digitalasset.canton.data.CantonTimestamp

import java.time.Duration
import org.lfdecentralizedtrust.splice.util.FrontendLoginUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import scala.util.Try

abstract class SvNonDevNetPreflightIntegrationTestBase
    extends FrontendIntegrationTestWithSharedEnvironment("sv")
    with SvUiIntegrationTestUtil
    with DataExportTestUtil
    with FrontendLoginUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  protected def svNumber: Int
  protected val svName = s"sv$svNumber"
  protected val svUrlPrefix = if (svNumber == 1) "sv-2" else s"sv-$svNumber-eng"
  protected val svNamespace = s"sv-$svNumber"

  protected def svClient(implicit env: SpliceTestConsoleEnvironment) = sv_client(svName)
  protected def svValidatorClient(implicit env: SpliceTestConsoleEnvironment) = vc(
    s"${svName}Validator"
  )
  protected def svScanClient(implicit env: SpliceTestConsoleEnvironment) = scancl(s"${svName}Scan")

  "SV reports devnet=false" in { implicit env =>
    svClient.getDsoInfo().dsoRules.payload.isDevNet shouldBe false
  }

  val svUsername = s"admin@${svName}.com"
  val svPassword = sys.env(s"SV_WEB_UI_PASSWORD")

  "SV can login to the SV UI" in { _ =>
    val svUiUrl = s"https://sv.$svUrlPrefix.${sys.env("NETWORK_APPS_ADDRESS")}/"

    withFrontEnd("sv") { implicit webDriver =>
      completeAuth0LoginWithAuthorization(
        svUiUrl,
        svUsername,
        svPassword,
        () => find(id("logout-button")) should not be empty,
      )
    }
  }

  "SV can login to the Name Service UI" in { _ =>
    val ansUrl = s"https://cns.$svUrlPrefix.${sys.env("NETWORK_APPS_ADDRESS")}"

    withFrontEnd("sv") { implicit webDriver =>
      completeAuth0LoginWithAuthorization(
        ansUrl,
        svUsername,
        svPassword,
        // if id("ans-entries") is visible, that implies:
        // 1) the logout button is visible
        // 2) the DirectoryInstall has been created (and therefore the request won't be aborted and thus flake)
        () => find(id("ans-entries")) should not be empty,
      )
    }
  }

  "SV can login to the wallet UI" in { _ =>
    val walletUrl = s"https://wallet.$svUrlPrefix.${sys.env("NETWORK_APPS_ADDRESS")}/"

    withFrontEnd("sv") { implicit webDriver =>
      completeAuth0LoginWithAuthorization(
        walletUrl,
        svUsername,
        svPassword,
        () => find(id("logout-button")) should not be empty,
      )
    }
  }

  "The Scan UI is working" in { _ =>
    val scanUrl = s"https://scan.$svUrlPrefix.${sys.env("NETWORK_APPS_ADDRESS")}"

    withFrontEnd("sv") { implicit webDriver =>
      go to scanUrl
      import scala.concurrent.duration.*
      eventually(3.minutes) {
        val asOfRound = find(id("as-of-round")).value.text
        asOfRound should startWith("The content on this page is computed as of round: ")
        asOfRound should not be "The content on this page is computed as of round: --"
      }
    }
  }

  "Check readiness of SV applications" in { implicit env =>
    eventually() {
      forAll(
        Seq(
          svClient,
          svValidatorClient,
          svScanClient,
        )
      )(_.httpReady shouldBe true)
    }
  }

  "Check that open mining round 0 is open for 26h" in { implicit env =>
    val (openRounds, _) = svScanClient.getOpenAndIssuingMiningRounds()
    inside(openRounds.find(_.contract.payload.round.number == 0)) {
      case None =>
        val openRoundNumbers = openRounds.map(_.contract.payload.round.number)
        logger.info(
          s"OpenMiningRound 0 is no longer open, currently open rounds: $openRoundNumbers"
        )
      case Some(round) =>
        val diff = CantonTimestamp.assertFromInstant(
          round.contract.payload.targetClosesAt
        ) - CantonTimestamp.assertFromInstant(round.contract.payload.opensAt)
        // In theory this should be exact but I don't entirely trust that leap seconds or whatever don't break this
        // and we don't actually care about it being exact.
        diff should be < Duration.ofHours(26).plus(Duration.ofSeconds(1))
        diff should be > Duration.ofHours(26).minus(Duration.ofSeconds(1))
    }
  }

  "Check health status of sv cometBft node" in { implicit env =>
    svClient.cometBftNodeStatus().catchingUp shouldBe false
  }

  "Check that there is a recent participant identities backup on GCP" in { _ =>
    testRecentParticipantIdentitiesDump(svNamespace)
  }
}

final class Sv1NonDevNetPreflightIntegrationTest extends SvNonDevNetPreflightIntegrationTestBase {

  override protected def svNumber = 1

  "Check that sv-1 responds with a recent aggregated round" in { implicit env =>
    eventually() {
      val (openRounds, issuingRounds) = svScanClient.getOpenAndIssuingMiningRounds()
      if (openRounds.exists(_.contract.payload.round.number == 0)) {
        logger.info("Round 0 is still open, not expecting an aggregate")
      } else if (issuingRounds.exists(_.contract.payload.round.number == 0)) {
        logger.info("Round 0 is still issuing, not expecting an aggregate")
      } else {
        val latestOpenMiningRound =
          Try(
            svScanClient
              .getLatestOpenMiningRound(env.environment.clock.now)
              .contract
              .payload
              .round
              .number
          ).getOrElse(fail("Could not get latest open mining round from sv-1"))
        val latestAggregatedRound = Try(svScanClient.getRoundOfLatestData()._1)
          .getOrElse(fail("Could not get round of latest data from sv-1"))
        latestOpenMiningRound - latestAggregatedRound should be <= 7L
      }
    }
  }
}
