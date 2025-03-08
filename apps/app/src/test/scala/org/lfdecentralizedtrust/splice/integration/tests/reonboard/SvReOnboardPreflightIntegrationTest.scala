package org.lfdecentralizedtrust.splice.integration.tests.reonboard

import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.runbook.{
  PreflightIntegrationTestUtil,
  SvUiPreflightIntegrationTestUtil,
}
import org.lfdecentralizedtrust.splice.sv.util.AnsUtil
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  SvFrontendTestUtil,
  WalletFrontendTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import org.scalatest.time.{Minute, Span}

import scala.concurrent.duration.DurationInt

class SvReOnboardPreflightIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("validator", "sv")
    with SvUiPreflightIntegrationTestUtil
    with SvFrontendTestUtil
    with PreflightIntegrationTestUtil
    with FrontendLoginUtil
    with WalletFrontendTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  protected def sv1ScanClient(implicit env: SpliceTestConsoleEnvironment) = scancl("sv1Scan")

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  private val svWalletUrl = s"https://wallet.sv.${sys.env("NETWORK_APPS_ADDRESS")}/"
  private val svUsername = s"admin@sv-dev.com"

  private val validatorWalletUrl =
    s"https://wallet.validator.${sys.env("NETWORK_APPS_ADDRESS")}/"
  private val validatorUsername = s"admin@validator.com"

  private val password = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD");

  private val usdTappedInOffboardTest = BigDecimal("100000")

  "Validator create a transfer offer to the reonboarded SV" in { implicit env =>
    val (_, offboardedSvParty) = withFrontEnd("validator") { implicit webDriver =>
      actAndCheck(
        s"Logging in to wallet at ${validatorWalletUrl}", {
          completeAuth0LoginWithAuthorization(
            validatorWalletUrl,
            validatorUsername,
            password,
            () => find(id("logout-button")) should not be empty,
          )
        },
      )(
        "User is logged in and onboarded",
        _ => {
          userIsLoggedIn()
          val usdText = find(id("wallet-balance-usd")).value.text.trim
          usdText should not be "..."
          val usd = parseAmountText(usdText, "USD")

          usd should be >= usdTappedInOffboardTest

          val loggedInUser = seleniumText(find(id("logged-in-user")))
          val ansUtil = new AnsUtil(ansAcronym)
          if (loggedInUser.endsWith(ansUtil.entryNameSuffix)) {
            val entry = sv1ScanClient.lookupEntryByName(loggedInUser)
            PartyId.tryFromProtoPrimitive(entry.user)
          } else PartyId.tryFromProtoPrimitive(loggedInUser)
        },
      )
    }

    val (_, reonbardedSvParty) = withFrontEnd("sv") { implicit webDriver =>
      actAndCheck(
        s"Logging in to wallet at ${svWalletUrl}", {
          completeAuth0LoginWithAuthorization(
            svWalletUrl,
            svUsername,
            password,
            () => find(id("logout-button")) should not be empty,
          )
        },
      )(
        "User is logged in and onboarded and the amulets are recovered from offboarded SV",
        _ => {
          userIsLoggedIn()

          val loggedInEntry = seleniumText(find(id("logged-in-user")))
          loggedInEntry shouldBe s"da-helm-test-node.sv.$ansAcronym"

          val entry = sv1ScanClient.lookupEntryByName(loggedInEntry)
          PartyId.tryFromProtoPrimitive(entry.user)
        },
      )
    }

    reonbardedSvParty should not be offboardedSvParty

    val amuletPrice = withFrontEnd("validator") { implicit webDriver =>
      val amuletPrice = clue("Getting the amulet price") {
        val usdText = find(id("wallet-balance-usd")).value.text.trim
        val amuletText = find(id("wallet-balance-amulet")).value.text.trim

        val amuletAcronym = sv1ScanClient.getSpliceInstanceNames().amuletNameAcronym
        val amulet = parseAmountText(amuletText, amuletAcronym)
        val usd = parseAmountText(usdText, "USD")

        val amuletPrice = (usd / amulet).setScale(10, BigDecimal.RoundingMode.HALF_UP)
        logger.info(s"Amulet price: $amuletPrice")

        amuletPrice
      }
      clue(s"Creating transfer offer for: $reonbardedSvParty") {
        createTransferOffer(
          reonbardedSvParty,
          usdTappedInOffboardTest / amuletPrice,
          90,
          "p2ptransfer",
        )
      }
      amuletPrice
    }

    withFrontEnd("sv") { implicit webDriver =>
      val acceptButton = eventually() {
        findAll(className("transfer-offer")).toSeq.headOption match {
          case Some(element) =>
            element.childWebElement(className("transfer-offer-accept"))
          case None => fail("failed to find transfer offer")
        }
      }

      actAndCheck(timeUntilSuccess = 30.seconds)(
        "Accept transfer offer", {
          click on acceptButton
          click on "navlink-transactions"
        },
      )(
        "Transfer appears in transactions log",
        _ => {
          val rows = findAll(className("tx-row")).toSeq
          val expectedRow = rows.filter { row =>
            val transaction = readTransactionFromRow(row)
            transaction.partyDescription.exists(_.contains(offboardedSvParty.toProtoPrimitive))
          }
          inside(expectedRow) { case Seq(tx) =>
            val transaction = readTransactionFromRow(tx)
            transaction.action should matchText("Received")
            transaction.ccAmount should beWithin(
              usdTappedInOffboardTest / amuletPrice - smallAmount,
              usdTappedInOffboardTest / amuletPrice,
            )
            transaction.usdAmount should beWithin(
              usdTappedInOffboardTest - smallAmount,
              usdTappedInOffboardTest,
            )
          }
        },
      )
    }
  }
}
