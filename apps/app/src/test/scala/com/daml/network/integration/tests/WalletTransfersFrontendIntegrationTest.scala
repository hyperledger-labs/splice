package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletFrontendTestUtil, WalletTestUtil}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.UUID

class WalletTransfersFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice", "bob")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  private val coinPrice = 2
  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withCoinPrice(coinPrice)

  "A wallet UI" should {

    "create a p2p transfer" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceDirectoryName = perTestCaseName("alice.cns")

      val bobUserParty = setupForTestWithDirectory(bobWallet, bobValidator)
      val bobDirectoryName = perTestCaseName("bob.cns")

      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3.5)
      val expiryDays = 10

      // setup alice and bob
      onboardWalletUser(aliceWallet, aliceValidator)
      onboardWalletUser(bobWallet, bobValidator)
      createDirectoryEntry(aliceUserParty, aliceDirectory, aliceDirectoryName, aliceWallet, cc)
      createDirectoryEntry(bobUserParty, bobDirectory, bobDirectoryName, bobWallet, cc)

      bobWallet.listTransferOffers() shouldBe empty

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        actAndCheck(
          "alice creates transfer offer", {
            createTransferOffer(bobUserParty, transferAmount, expiryDays)
          },
        )(
          "alice is redirected to /transactions & bob observes transfer offer",
          _ => {
            currentUrl should endWith("/transactions")

            bobWallet.listTransferOffers() should have size 1
            val transfer = bobWallet.listTransferOffers().head.payload
            val tenDaysFromNow = Instant.now().plus(expiryDays.toLong, ChronoUnit.DAYS)
            val timeDiff = ChronoUnit.MINUTES.between(transfer.expiresAt, tenDaysFromNow)

            transfer.amount.amount.floatValue() shouldBe transferAmount.floatValue
            transfer.description shouldBe "by party ID"
            transfer.sender shouldBe aliceUserParty.toProtoPrimitive
            transfer.receiver shouldBe bobUserParty.toProtoPrimitive

            // Allowing 1 min of correctness in the transfer dates
            assertInRange(BigDecimal(timeDiff), (BigDecimal(0), BigDecimal(1)))
          },
        )
      }

      withFrontEnd("bob") { implicit webDriver =>
        actAndCheck(
          "Bob goes to his wallet", {
            browseToBobWallet(bobWallet.config.ledgerApiUser)
          },
        )(
          "He sees the transfer offer",
          _ => {
            val offerCards = findAll(className("transfer-offer")).toList

            offerCards.size shouldBe 1

            inside(offerCards) { case Seq(offerCard) =>
              offerCard.childElement(className("transfer-offer-sender")).text should matchText(
                expectedCns(aliceUserParty, aliceDirectoryName)
              )

              offerCard.childElement(className("transfer-offer-cc-amount")).text should matchText(
                s"+ $transferAmount CC"
              )

              offerCard
                .childElement(className("transfer-offer-usd-amount-rate"))
                .text should matchText(
                s"7 USD @ ${BigDecimal(1) / coinPrice} CC/USD"
              )
            }
          },
        )
      }
    }

    "show a list of transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceDirectoryName = perTestCaseName("alice.cns")

      val bobUserParty = setupForTestWithDirectory(bobWallet, bobValidator)
      val bobDirectoryName = perTestCaseName("bob.cns")
      val bobDirectoryDisplay = expectedCns(bobUserParty, bobDirectoryName)

      val transferExpiry = CantonTimestamp.now().plusSeconds(100)

      val expectedExpiry =
        DateTimeFormatter
          .ofPattern("MM/dd/yyyy HH:mm")
          .format(LocalDateTime.ofInstant(transferExpiry.toInstant, ZoneOffset.UTC))

      onboardWalletUser(aliceWallet, aliceValidator)
      onboardWalletUser(bobWallet, bobValidator)
      createDirectoryEntry(aliceUserParty, aliceDirectory, aliceDirectoryName, aliceWallet)
      createDirectoryEntry(bobUserParty, bobDirectory, bobDirectoryName, bobWallet)

      actAndCheck(
        " Bob creates transfer offer to alice",
        bobWallet.createTransferOffer(
          aliceUserParty,
          BigDecimal(1),
          "Bobo transfer to Alice",
          transferExpiry,
          UUID.randomUUID().toString,
        ),
      )("alice observes transfer offer", _ => aliceWallet.listTransferOffers() should have size 1)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        eventually() {
          val offerCards = findAll(className("transfer-offer")).toList

          offerCards.size shouldBe 1

          inside(offerCards) { case Seq(offerCard) =>
            offerCard.childElement(className("transfer-offer-sender")).text should matchText(
              bobDirectoryDisplay
            )

            offerCard.childElement(className("transfer-offer-expiry")).text should matchText(
              s"Expires $expectedExpiry"
            )

            offerCard.childElement(className("transfer-offer-cc-amount")).text should matchText(
              "+ 1 CC"
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

    "not show transfer offers I created" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceDirectoryName = perTestCaseName("alice.cns")

      val bobUserParty = setupForTestWithDirectory(bobWallet, bobValidator)
      val bobDirectoryName = perTestCaseName("bob.cns")

      val transferExpiry = CantonTimestamp.now().plusSeconds(100)

      onboardWalletUser(aliceWallet, aliceValidator)
      onboardWalletUser(bobWallet, bobValidator)
      createDirectoryEntry(aliceUserParty, aliceDirectory, aliceDirectoryName, aliceWallet)
      createDirectoryEntry(bobUserParty, bobDirectory, bobDirectoryName, bobWallet)

      actAndCheck(
        "Alice creates transfer offer to bob",
        aliceWallet.createTransferOffer(
          bobUserParty,
          BigDecimal(1),
          "Alice transfer to Bob",
          transferExpiry,
          UUID.randomUUID().toString,
        ),
      )(
        "alice has an outgoing transfer offer",
        _ => aliceWallet.listTransferOffers() should have size 1,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        clue("Alice can't see transfer offers she created") {
          eventually() {
            findAll(className("transfer-offer")).toList should have size 0
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

      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3)

      // setup alice and bob
      onboardWalletUser(aliceWallet, aliceValidator)
      onboardWalletUser(bobWallet, bobValidator)
      createDirectoryEntry(aliceUserParty, aliceDirectory, aliceDirectoryName, aliceWallet, cc)
      createDirectoryEntry(bobUserParty, bobDirectory, bobDirectoryName, bobWallet, cc)

      // transfer from bob to alice
      actAndCheck(
        "bob creates transfer offer",
        withFrontEnd("bob") { implicit webDriver =>
          browseToBobWallet(bobWallet.config.ledgerApiUser)
          createTransferOffer(aliceUserParty, transferAmount, expiryDays = 1)
        },
      )("alice observes transfer offer", _ => aliceWallet.listTransferOffers() should have size 1)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

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

      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3)

      // setup alice and bob
      onboardWalletUser(aliceWallet, aliceValidator)
      onboardWalletUser(bobWallet, bobValidator)
      createDirectoryEntry(aliceUserParty, aliceDirectory, aliceDirectoryName, aliceWallet, cc)
      createDirectoryEntry(bobUserParty, bobDirectory, bobDirectoryName, bobWallet, cc)

      // transfer from bob to alice
      actAndCheck(
        "bob creates transfer offer",
        withFrontEnd("bob") { implicit webDriver =>
          browseToBobWallet(bobWallet.config.ledgerApiUser)
          createTransferOffer(aliceUserParty, transferAmount, expiryDays = 1)
        },
      )("alice observes transfer offer", _ => aliceWallet.listTransferOffers() should have size 1)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        eventually() {
          findAll(className("transfer-offer")) should have size 1
        }

        actAndCheck(
          "Alice rejects the offer", {
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
