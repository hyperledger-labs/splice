package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletTestUtil}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.format.DateTimeFormatter
import java.time.{Duration, LocalDateTime, ZoneOffset}
import java.util.UUID

class WalletNewTransfersFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withCoinPrice(2)

  "A wallet UI" should {
    val aliceWalletNewPort = 3007

    "show a list of transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceDirectoryName = perTestCaseName("alice.cns")
      val aliceDirectoryDisplay = expectedCns(aliceUserParty, aliceDirectoryName)
      val transferExpiry = CantonTimestamp.now().plusSeconds(100)

      val expectedExpiry =
        DateTimeFormatter
          .ofPattern("MM/dd/yyyy HH:mm")
          .format(LocalDateTime.ofInstant(transferExpiry.toInstant, ZoneOffset.UTC))

      onboardWalletUser(aliceWallet, aliceValidator)
      createDirectoryEntry(aliceUserParty, aliceDirectory, aliceDirectoryName, aliceWallet)

      aliceWallet.createTransferOffer(
        aliceUserParty,
        BigDecimal(1),
        "Alice self transfer",
        transferExpiry,
        UUID.randomUUID().toString,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToWallet(aliceWalletNewPort, aliceDamlUser)
        eventually() {
          val offerCards = findAll(className("transfer-offer")).toList

          offerCards.size shouldBe 1

          inside(offerCards) { case Seq(offerCard) =>
            offerCard.childElement(className("transfer-offer-sender")).text should matchText(
              aliceDirectoryDisplay
            )

            offerCard.childElement(className("transfer-offer-expiry")).text should matchText(
              s"Expires $expectedExpiry"
            )

            offerCard.childElement(className("transfer-offer-cc-amount")).text should matchText(
              "+ 1.0 CC"
            )

            offerCard
              .childElement(className("transfer-offer-usd-amount-rate"))
              .text should matchText(
              "2 USD @ 0.5 CC/USD"
            )
          }
        }
      }
    }

    "allow accepting transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceDirectoryName = perTestCaseName("alice.cns")

      val bobUserParty = setupForTestWithDirectory(bobWallet, bobValidator)
      val bobDirectoryName = perTestCaseName("bob.cns")

      val transferExpiry = CantonTimestamp.now().plus(Duration.ofDays(5))
      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3)

      // setup alice and bob
      onboardWalletUser(aliceWallet, aliceValidator)
      onboardWalletUser(bobWallet, bobValidator)
      createDirectoryEntry(aliceUserParty, aliceDirectory, aliceDirectoryName, aliceWallet, cc)
      createDirectoryEntry(bobUserParty, bobDirectory, bobDirectoryName, bobWallet, cc)

      // transfer from bob to alice
      bobWallet.createTransferOffer(
        aliceUserParty,
        transferAmount,
        "Bob transferring to Alice",
        transferExpiry,
        UUID.randomUUID().toString,
      )

      aliceWallet.listTransferOffers().size shouldBe 1

      withFrontEnd("alice") { implicit webDriver =>
        browseToWallet(aliceWalletNewPort, aliceDamlUser)

        eventually() {
          findAll(className("transfer-offer")).toList.size shouldBe 1
        }

        actAndCheck(
          "Alice accepts the offer", {
            click on className("transfer-offer-accept")
          },
        )(
          "Alice sees no more pending transfer offers",
          _ => {
            findAll(className("transfer-offer")).toList.size shouldBe 0
            assertInRange(bobWallet.balance().unlockedQty, (BigDecimal(6), BigDecimal(7)))
            assertInRange(aliceWallet.balance().unlockedQty, (BigDecimal(12), BigDecimal(13)))
          },
        )
      }
    }

    "allow rejecting transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceDirectoryName = perTestCaseName("alice.cns")

      val bobUserParty = setupForTestWithDirectory(bobWallet, bobValidator)
      val bobDirectoryName = perTestCaseName("bob.cns")

      val transferExpiry = CantonTimestamp.now().plus(Duration.ofDays(5))
      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3)

      // setup alice and bob
      onboardWalletUser(aliceWallet, aliceValidator)
      onboardWalletUser(bobWallet, bobValidator)
      createDirectoryEntry(aliceUserParty, aliceDirectory, aliceDirectoryName, aliceWallet, cc)
      createDirectoryEntry(bobUserParty, bobDirectory, bobDirectoryName, bobWallet, cc)

      // transfer from bob to alice
      bobWallet.createTransferOffer(
        aliceUserParty,
        transferAmount,
        "Bob transferring to Alice",
        transferExpiry,
        UUID.randomUUID().toString,
      )

      aliceWallet.listTransferOffers().size shouldBe 1

      withFrontEnd("alice") { implicit webDriver =>
        browseToWallet(aliceWalletNewPort, aliceDamlUser)

        eventually() {
          findAll(className("transfer-offer")) should have size 1
        }

        actAndCheck(
          "Alice accepts the offer", {
            click on className("transfer-offer-reject")
          },
        )(
          "Alice sees no more pending transfer offers",
          _ => {
            findAll(className("transfer-offer")) should have size 0
            assertInRange(bobWallet.balance().unlockedQty, (BigDecimal(9), BigDecimal(10)))
            assertInRange(aliceWallet.balance().unlockedQty, (BigDecimal(9), BigDecimal(10)))
          },
        )
      }
    }
  }
}
