package com.daml.network.integration.tests.runbook

import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.config.GcpBucketConfig
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions as http
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.daml.network.util.CoinConfigSchedule
import com.digitalasset.canton.data.CantonTimestamp

import java.time.{Duration, Instant}
import com.daml.network.util.{FrontendLoginUtil, GcpBucket}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.nio.charset.StandardCharsets

class NonDevNetPreflightIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv")
    with SvUiIntegrationTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  override def sv1Client(implicit env: CNNodeTestConsoleEnvironment) = svcl("sv1")
  def sv1ValidatorClient(implicit env: CNNodeTestConsoleEnvironment) = vc("sv1Validator")
  def validator1Client(implicit env: CNNodeTestConsoleEnvironment) = vc("validator1")
  def sv1ScanClient(implicit env: CNNodeTestConsoleEnvironment) = scancl("sv1Scan")
  def sv1DirectoryClient(implicit env: CNNodeTestConsoleEnvironment) = rdp("sv1Directory")
  def splitwellClient(implicit env: CNNodeTestConsoleEnvironment) = rsw("splitwell")
  def splitwellValidatorClient(implicit env: CNNodeTestConsoleEnvironment) = vc(
    "splitwellValidator"
  )

  "SVs 1 reports devnet=false" in { implicit env =>
    sv1Client.getSvcInfo().svcRules.payload.isDevNet shouldBe false
  }

  val svUsername = s"admin@sv1.com"
  val svPassword = sys.env(s"SV_WEB_UI_PASSWORD")

  "SV1 can login to the SV UI" in { _ =>
    val svUiUrl = s"https://sv.sv-1.svc.${sys.env("NETWORK_APPS_ADDRESS")}/"

    withFrontEnd("sv") { implicit webDriver =>
      completeAuth0LoginWithAuthorization(
        svUiUrl,
        svUsername,
        svPassword,
        () => find(id("logout-button")) should not be empty,
      )
    }
  }

  "SV1 can login to the directory UI" in { _ =>
    val directoryUrl = s"https://directory.sv-1.svc.${sys.env("NETWORK_APPS_ADDRESS")}"

    withFrontEnd("sv") { implicit webDriver =>
      completeAuth0LoginWithAuthorization(
        directoryUrl,
        svUsername,
        svPassword,
        () => find(id("logout-button")) should not be empty,
      )
    }
  }

  "SV1 can login to the wallet UI" in { _ =>
    val walletUrl = s"https://wallet.sv-1.svc.${sys.env("NETWORK_APPS_ADDRESS")}/"

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
    val scanUrl = s"https://scan.sv-1.svc.${sys.env("NETWORK_APPS_ADDRESS")}"

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

  "Check readiness of applications" in { implicit env =>
    eventually() {
      forAll(
        Seq(
          sv1Client,
          sv1ValidatorClient,
          sv1ScanClient,
          sv1DirectoryClient,
          validator1Client,
          splitwellValidatorClient,
        )
      )(_.httpReady shouldBe true)
      splitwellClient.health.status.isActive shouldBe Some(true)
    }
  }

  "Check latest open mining round from SV1 scan-app has been open for < 1.3 ticks" in {
    implicit env =>
      val round = sv1ScanClient.getLatestOpenMiningRound(env.environment.clock.now).contract.payload
      checkRoundWithinTickDuration(round.opensAt, 1.3)
  }

  "Check latest open mining round from SV1 sv-app has been open for < 1.3 ticks" in {
    implicit env =>
      val round = sv1Client.getSvcInfo().latestMiningRound.payload
      checkRoundWithinTickDuration(round.opensAt, 1.3)
  }

  // TODO(#6073) Replace this by only checking that a recent snapshot exists
  // instead of triggering one.
  "trigger ACS snapshot and check that it can be downloaded and decoded" in { implicit env =>
    val result = sv1Client.triggerAcsDump()
    val bucket = new GcpBucket(GcpBucketConfig.inferForTesting, loggerFactory)
    val dump = new String(bucket.readBytesFromBucket(result.filename), StandardCharsets.UTF_8)
    io.circe.parser.decode[http.GetAcsStoreDumpResponse](dump) should matchPattern {
      case Right(_) =>
    }
  }

  private def checkRoundWithinTickDuration(round: Instant, factor: Double)(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val tickDuration: RelTime = CoinConfigSchedule(
      sv1ScanClient
        .getCoinRules()
        .toReadyContract
        .getOrElse(fail("coinRules is currently in-flight"))
    ).getConfigAsOf(env.environment.clock.now).tickDuration
    (env.environment.clock.now - CantonTimestamp.assertFromInstant(
      round
    )) should be < Duration.ofNanos(tickDuration.microseconds * (1000 * factor).longValue())
  }
}
