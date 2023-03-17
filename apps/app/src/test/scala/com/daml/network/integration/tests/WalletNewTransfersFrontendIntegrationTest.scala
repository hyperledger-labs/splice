package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletTestUtil}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.UUID

class WalletNewTransfersFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
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
  }
}
