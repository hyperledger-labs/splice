package org.lfdecentralizedtrust.splice.integration.tests.runbook

import com.digitalasset.canton.topology.SynchronizerId
import io.circe.parser.{parse as parseJson}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.{
  AnsFrontendTestUtil,
  Auth0Util,
  FrontendLoginUtil,
  SvTestUtil,
  WalletFrontendTestUtil,
}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Random

abstract class RunbookSvPreflightIntegrationTestBase
    extends FrontendIntegrationTestWithSharedEnvironment("sv")
    with PreflightIntegrationTestUtil
    with SvUiPreflightIntegrationTestUtil
    with FrontendLoginUtil
    with WalletFrontendTestUtil
    with AnsFrontendTestUtil
    with SvTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.svPreflightTopology(
      this.getClass.getSimpleName()
    )

  protected def svUsername: String
  protected def isDevNet: Boolean
  protected def svPassword: String =
    if (isDevNet) sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD") else sys.env(s"SV_WEB_UI_PASSWORD")
  protected lazy val validatorUserPassword = sys.env(s"VALIDATOR_WEB_UI_PASSWORD")
  val scanUrl = s"https://scan.sv.${sys.env("NETWORK_APPS_ADDRESS")}"

  val walletUrl = s"https://wallet.sv.${sys.env("NETWORK_APPS_ADDRESS")}/"

  "The SV UI of the node is working as expected" in { _ =>
    val svUiUrl = s"https://sv.sv.${sys.env("NETWORK_APPS_ADDRESS")}/";
    withFrontEnd("sv") { implicit webDriver =>
      testSvUi(svUiUrl, svUsername, svPassword, None, Seq())
    }
  }

  "CometBFT is working" in { _ =>
    val svUiUrl = s"https://sv.sv.${sys.env("NETWORK_APPS_ADDRESS")}/";

    withFrontEnd("sv") { implicit webDriver =>
      actAndCheck(
        s"Logging in to SV UI at: ${svUiUrl}", {
          completeAuth0LoginWithAuthorization(
            svUiUrl,
            svUsername,
            svPassword,
            () => find(id("logout-button")) should not be empty,
          )

          eventuallyClickOn(id("information-tab-cometBft-debug"))
        },
      )(
        s"We see all other SVs as peers",
        _ => {
          inside(find(id("comet-bft-debug-network"))) { case Some(e) =>
            if (isDevNet) {
              forAll(Range(1, 5)) { _ =>
                e.text should include(s"\"moniker\": \"${getSvName(1)}\"")
              }
            } else {
              forAll(Range(1, 2)) { _ =>
                e.text should include(s"\"moniker\": \"Digital-Asset-2\"")
              }
            }
          }
        },
      )
    }
  }

  "The SV can log in to their wallet" in { implicit env =>
    withFrontEnd("sv") { implicit webDriver =>
      actAndCheck(
        s"Logging in to wallet at ${walletUrl}", {
          completeAuth0LoginWithAuthorization(
            walletUrl,
            svUsername,
            svPassword,
            () => find(id("logout-button")) should not be empty,
          )
        },
      )(
        "User is logged in and onboarded",
        _ => {
          userIsLoggedIn()
        },
      )
      if (isDevNet) { // can't tap in NonDevNet
        tapAmulets(100)
      }
    }
  }

  "The SV rewards are claimed by the SV, with 33.33% going to validator1" in { implicit env =>
    val svClient = sv_client("sv")
    val sv1ScanClient = scancl("sv1Scan")

    val dsoInfo = svClient.getDsoInfo()
    val svParty = dsoInfo.svParty.toProtoPrimitive
    val svInfo = dsoInfo.dsoRules.payload.svs.asScala.get(svParty).value
    val joinedAsOfRound = svInfo.joinedAsOfRound.number
    val earliestOpenRound =
      sv1ScanClient.getOpenAndIssuingMiningRounds()._1.minBy(_.payload.opensAt).payload.round.number

    logger.debug(
      s"Earliest open round: $earliestOpenRound, sv runbook joined as of round: $joinedAsOfRound"
    )
    // Make sure that the SV would've received & claimed SvRewardCoupons
    if (earliestOpenRound >= joinedAsOfRound + 3) {
      def checkPresenceOfEntriesInSvAndValidator(): Unit = {
        withFrontEnd("sv") { implicit webDriver =>
          val (_, svEntries) = actAndCheck(
            s"Logging in to SV wallet at ${walletUrl}", {
              completeAuth0LoginWithAuthorization(
                walletUrl,
                svUsername,
                svPassword,
                () => find(id("logout-button")) should not be empty,
              )
            },
          )(
            "There's SV Reward collected entries",
            _ => {
              val txs = findAll(className("tx-row")).toSeq.map(readTransactionFromRow)

              val svRewardEntries = txs.filter(_.svRewardsUsed > 0)
              svRewardEntries should not be empty
              svRewardEntries
            },
          )

          val validator1WalletUrl = s"https://wallet.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/"
          val (_, validatorEntries) = actAndCheck(
            s"Logging in to validator1 wallet at ${validator1WalletUrl}", {
              completeAuth0LoginWithAuthorization(
                validator1WalletUrl,
                "admin@validator1.com",
                validatorUserPassword,
                () => find(id("logout-button")) should not be empty,
              )
            },
          )(
            "There's SV Reward collected entries",
            _ => {
              val txs = findAll(className("tx-row")).toSeq.map(readTransactionFromRow)

              val svRewardEntries = txs.filter(_.svRewardsUsed > 0)
              svRewardEntries should not be empty
              svRewardEntries
            },
          )
          (svEntries, validatorEntries)
        }
      }

      // Both validator1 and sv can claim more than one coupon at once.
      // Furthermore, other SVs might not be available to claim their rewards.
      // Both of these situations make the amounts claimed in a given round to not be constant.
      // This means the following checks are flaky:
      // - The amount is 33.33% or 66.67% of what an SV should get: #10785
      // - The amount of what validator1 gets is proportional to each other: #12392
      // Thus, the only option is to assert that both SV and validator1 receive SV rewards (svRewardsUsed > 0),
      // and let other tests (SvTimeBasedRewardCouponIntegrationTest, WeightDistributionForSvTest, TestSvRewards.daml)
      // verify that the amounts are correct.
      clue("Both SV and validator1 have received rewards") {
        eventually() {
          checkPresenceOfEntriesInSvAndValidator()
        }
      }
    } else {
      logger.debug(
        "Skipping checking SV rewards, the SV might not yet have claimed any SV rewards."
      )
    }
  }

  "The Scan UI is working" in { _ =>
    withFrontEnd("sv") { implicit webDriver =>
      go to scanUrl
      eventually(3.minutes) {
        val asOfRound = find(id("as-of-round")).value.text
        asOfRound should startWith("The content on this page is computed as of round: ")
        asOfRound should not be "The content on this page is computed as of round: --"
      }
    }
  }

  "The Name Service UI is working" in { implicit env =>
    val ansUrl = s"https://cns.sv.${sys.env("NETWORK_APPS_ADDRESS")}"
    val ansName =
      s"da-test-${Random.alphanumeric.take(10).mkString.toLowerCase}.unverified.$ansAcronym"

    withFrontEnd("sv") { implicit webDriver =>
      def login(): Unit = {
        actAndCheck(
          s"Logging in to ANS at $ansUrl", {
            completeAuth0LoginWithAuthorization(
              ansUrl,
              svUsername,
              svPassword,
              () => find(id("logout-button")) should not be empty,
            )
          },
        )(
          "User is logged in and onboarded",
          _ => {
            userIsLoggedIn()
          },
        )

      }
      if (isDevNet) { // SV missing Amulet in NonDevNet
        reserveAnsNameFor(
          () => login(),
          ansName,
          "1.0000000000",
          "USD",
          "90 days",
          ansAcronym,
        )
        clue(s"Reserved ANS name can be looked up via scan") {
          val svScanClient = scancl("svTestScan")
          eventuallySucceeds(3.minutes) {
            svScanClient.lookupEntryByName(ansName)
          }
        }
      }
    }
  }

  "Key API endpoints are reachable and functional" in { implicit env =>
    val token = eventuallySucceeds() {
      Auth0Util.getAuth0ClientCredential(
        sys.env("SPLICE_OAUTH_SV_TEST_CLIENT_ID_VALIDATOR"),
        "https://validator.example.com/api",
        sys.env("SPLICE_OAUTH_SV_TEST_AUTHORITY"),
      )(noTracingLogger)
    }
    val svValidatorClient = vc("svTestValidator").copy(token = Some(token))
    val svScanClient = scancl("svTestScan")
    val sv1ScanClient = scancl("sv1Scan")
    val participantId = clue("Can dump participant identities from SV validator") {
      svValidatorClient.dumpParticipantIdentities().id
    }
    val activeSynchronizer = clue("Can get active domain from Scan") {
      val svActiveDomain = SynchronizerId.tryFromString(
        svScanClient
          .getAmuletConfigAsOf(env.environment.clock.now)
          .decentralizedSynchronizer
          .activeSynchronizer
      )
      val sv1ActiveDomain = SynchronizerId.tryFromString(
        sv1ScanClient
          .getAmuletConfigAsOf(env.environment.clock.now)
          .decentralizedSynchronizer
          .activeSynchronizer
      )
      svActiveDomain shouldBe sv1ActiveDomain
      svActiveDomain
    }
    clue("Can get hosting participant id for a party from Scan") {
      eventually() {
        val participantIdFromSv = svScanClient.getPartyToParticipant(
          activeSynchronizer,
          svValidatorClient.getValidatorPartyId(),
        )
        val participantIdFromSv1 = sv1ScanClient.getPartyToParticipant(
          activeSynchronizer,
          svValidatorClient.getValidatorPartyId(),
        )
        participantIdFromSv shouldBe participantIdFromSv1
      }
    }
    clue("Can get member traffic status from Scan") {
      eventually() {
        val svTrafficStatus =
          svScanClient.getMemberTrafficStatus(activeSynchronizer, participantId.member)
        val sv1TrafficStatus =
          sv1ScanClient.getMemberTrafficStatus(activeSynchronizer, participantId.member)
        svTrafficStatus shouldBe sv1TrafficStatus
      }
    }
  }

  "Info service is reachable and returns a JSON object" in { _ =>
    val infoUrl = s"https://info.sv.${sys.env("NETWORK_APPS_ADDRESS")}"
    val requests = ArraySeq("", "/runtime/dso.json")
      .map(path =>
        HttpRequest
          .newBuilder()
          .uri(URI.create(s"$infoUrl$path"))
          .build()
      )
    val client = HttpClient.newHttpClient()

    for (request <- requests) {
      clue(s"Testing info endpoint ${request.uri()}") {
        eventuallySucceeds(timeUntilSuccess = 5.minutes) {
          val response = client.send(request, HttpResponse.BodyHandlers.ofString())
          response.statusCode() shouldBe 200
          val json = parseJson(response.body()).valueOrFail("Response body must be a valid JSON")
          json.isObject shouldBe true
        }
      }
    }
  }
}

final class RunbookSvPreflightIntegrationTest extends RunbookSvPreflightIntegrationTestBase {
  override protected def svUsername = s"admin@sv-dev.com";
  override protected def isDevNet = true
}

final class RunbookSvNonDevNetPreflightIntegrationTest
    extends RunbookSvPreflightIntegrationTestBase {
  override protected def svUsername = s"admin@sv.com";
  override protected def isDevNet = false
}
