package com.daml.network.integration.tests.runbook

import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.config.GcpBucketConfig
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions as http
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.daml.network.util.{CoinConfigSchedule, ParticipantIdentitiesTestUtil}
import com.digitalasset.canton.data.CantonTimestamp

import java.time.{Duration, Instant}
import com.daml.network.util.{FrontendLoginUtil, GcpBucket}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.nio.file.Paths

abstract class SvNonDevNetPreflightIntegrationTestBase
    extends FrontendIntegrationTestWithSharedEnvironment("sv")
    with SvUiIntegrationTestUtil
    with ParticipantIdentitiesTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  protected def svNumber: Int
  protected val svName = s"sv$svNumber"
  protected val svUrlPrefix = s"sv-$svNumber"

  protected def svClient(implicit env: CNNodeTestConsoleEnvironment) = svcl(svName)
  protected def svValidatorClient(implicit env: CNNodeTestConsoleEnvironment) = vc(
    s"${svName}Validator"
  )
  protected def svScanClient(implicit env: CNNodeTestConsoleEnvironment) = scancl(s"${svName}Scan")

  "SV reports devnet=false" in { implicit env =>
    svClient.getSvcInfo().svcRules.payload.isDevNet shouldBe false
  }

  val svUsername = s"admin@${svName}.com"
  val svPassword = sys.env(s"SV_WEB_UI_PASSWORD")

  "SV can login to the SV UI" in { _ =>
    val svUiUrl = s"https://sv.$svUrlPrefix.svc.${sys.env("NETWORK_APPS_ADDRESS")}/"

    withFrontEnd("sv") { implicit webDriver =>
      completeAuth0LoginWithAuthorization(
        svUiUrl,
        svUsername,
        svPassword,
        () => find(id("logout-button")) should not be empty,
      )
    }
  }

  "SV can login to the directory UI" in { _ =>
    val directoryUrl = s"https://directory.$svUrlPrefix.svc.${sys.env("NETWORK_APPS_ADDRESS")}"

    withFrontEnd("sv") { implicit webDriver =>
      completeAuth0LoginWithAuthorization(
        directoryUrl,
        svUsername,
        svPassword,
        // if id("directory-entries") is visible, that implies:
        // 1) the logout button is visible
        // 2) the DirectoryInstall has been created (and therefore the request won't be aborted and thus flake)
        () => find(id("directory-entries")) should not be empty,
      )
    }
  }

  "SV can login to the wallet UI" in { _ =>
    val walletUrl = s"https://wallet.$svUrlPrefix.svc.${sys.env("NETWORK_APPS_ADDRESS")}/"

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
    val scanUrl = s"https://scan.$svUrlPrefix.svc.${sys.env("NETWORK_APPS_ADDRESS")}"

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

  "Check latest open mining round from SV scan-app has been open for < 1.3 ticks" in {
    implicit env =>
      val round = svScanClient.getLatestOpenMiningRound(env.environment.clock.now).contract.payload
      checkRoundWithinTickDuration(round.opensAt, 1.3)
  }

  "Check latest open mining round from SV sv-app has been open for < 1.3 ticks" in { implicit env =>
    val round = svClient.getSvcInfo().latestMiningRound.payload
    checkRoundWithinTickDuration(round.opensAt, 1.3)
  }

  "Check health status of sv cometBft node" in { implicit env =>
    svClient.cometBftNodeStatus().catchingUp shouldBe false
  }

  "Check that there is a recent participant identity backup on GCP" in { implicit env =>
    testRecentParticipantIdentitiesDump(svValidatorClient)
  }

  private def checkRoundWithinTickDuration(round: Instant, factor: Double)(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val tickDuration: RelTime = CoinConfigSchedule(
      svScanClient
        .getCoinRules()
        .toReadyContract
        .getOrElse(fail("coinRules is currently in-flight"))
    ).getConfigAsOf(env.environment.clock.now).tickDuration
    (env.environment.clock.now - CantonTimestamp.assertFromInstant(
      round
    )) should be < Duration.ofNanos(tickDuration.microseconds * (1000 * factor).longValue())
  }
}

final class Sv1NonDevNetPreflightIntegrationTest extends SvNonDevNetPreflightIntegrationTestBase {

  override protected def svNumber = 1

  // TODO(#6073) Replace this by only checking that a recent snapshot exists
  // instead of triggering one.
  "trigger ACS snapshot and check that it can be downloaded and decoded" in { implicit env =>
    val result = sv1Client.triggerAcsDump()
    val bucket = new GcpBucket(GcpBucketConfig.inferForCluster, loggerFactory)
    val dump = bucket.readStringFromBucket(Paths.get(result.filename))
    io.circe.parser.decode[http.GetAcsStoreDumpResponse](dump) should matchPattern {
      case Right(_) =>
    }
  }
}

final class Sv2NonDevNetPreflightIntegrationTest extends SvNonDevNetPreflightIntegrationTestBase {
  override protected def svNumber = 2
}
