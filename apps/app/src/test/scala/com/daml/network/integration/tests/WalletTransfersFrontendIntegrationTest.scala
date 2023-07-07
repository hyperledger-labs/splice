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
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWalletClient, aliceValidatorBackend)
      val aliceDirectoryName = perTestCaseName("alice")

      val bobUserParty = setupForTestWithDirectory(bobWalletClient, bobValidatorBackend)
      val bobDirectoryName = perTestCaseName("bob")

      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3.5)
      val expiryDays = 10

      // setup alice and bob
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createDirectoryEntry(
        aliceUserParty,
        aliceDirectoryClient,
        aliceDirectoryName,
        aliceWalletClient,
        cc,
      )
      createDirectoryEntry(bobUserParty, bobDirectoryClient, bobDirectoryName, bobWalletClient, cc)

      bobWalletClient.listTransferOffers() shouldBe empty

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

            bobWalletClient.listTransferOffers() should have size 1
            val transfer = bobWalletClient.listTransferOffers().head.payload
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
            browseToBobWallet(bobWalletClient.config.ledgerApiUser)
          },
        )(
          "He sees the transfer offer",
          _ => {
            val offerCards = findAll(className("transfer-offer")).toList

            offerCards should have size (1)

            inside(offerCards) { case Seq(offerCard) =>
              seleniumText(
                offerCard.childElement(className("transfer-offer-sender"))
              ) should matchText(expectedCns(aliceUserParty, aliceDirectoryName))

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
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWalletClient, aliceValidatorBackend)
      val aliceDirectoryName = perTestCaseName("alice")

      val bobUserParty = setupForTestWithDirectory(bobWalletClient, bobValidatorBackend)
      val bobDirectoryName = perTestCaseName("bob")
      val bobDirectoryDisplay = expectedCns(bobUserParty, bobDirectoryName)

      val transferExpiry = CantonTimestamp.now().plusSeconds(100)

      val expectedExpiry =
        DateTimeFormatter
          .ofPattern("MM/dd/yyyy HH:mm")
          .format(LocalDateTime.ofInstant(transferExpiry.toInstant, ZoneOffset.UTC))

      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createDirectoryEntry(
        aliceUserParty,
        aliceDirectoryClient,
        aliceDirectoryName,
        aliceWalletClient,
      )
      createDirectoryEntry(bobUserParty, bobDirectoryClient, bobDirectoryName, bobWalletClient)

      actAndCheck(
        " Bob creates transfer offer to alice",
        bobWalletClient.createTransferOffer(
          aliceUserParty,
          BigDecimal(1),
          "Bobo transfer to Alice",
          transferExpiry,
          UUID.randomUUID().toString,
        ),
      )(
        "alice observes transfer offer",
        _ => aliceWalletClient.listTransferOffers() should have size 1,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        eventually() {
          val offerCards = findAll(className("transfer-offer")).toList

          offerCards should have size (1)

          inside(offerCards) { case Seq(offerCard) =>
            seleniumText(
              offerCard.childElement(className("transfer-offer-sender"))
            ) should matchText(bobDirectoryDisplay)

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
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWalletClient, aliceValidatorBackend)
      val aliceDirectoryName = perTestCaseName("alice")

      val bobUserParty = setupForTestWithDirectory(bobWalletClient, bobValidatorBackend)
      val bobDirectoryName = perTestCaseName("bob")

      val transferExpiry = CantonTimestamp.now().plusSeconds(100)

      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createDirectoryEntry(
        aliceUserParty,
        aliceDirectoryClient,
        aliceDirectoryName,
        aliceWalletClient,
      )
      createDirectoryEntry(bobUserParty, bobDirectoryClient, bobDirectoryName, bobWalletClient)

      actAndCheck(
        "Alice creates transfer offer to bob",
        aliceWalletClient.createTransferOffer(
          bobUserParty,
          BigDecimal(1),
          "Alice transfer to Bob",
          transferExpiry,
          UUID.randomUUID().toString,
        ),
      )(
        "alice has an outgoing transfer offer",
        _ => aliceWalletClient.listTransferOffers() should have size 1,
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
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWalletClient, aliceValidatorBackend)
      val aliceDirectoryName = perTestCaseName("alice")

      val bobUserParty = setupForTestWithDirectory(bobWalletClient, bobValidatorBackend)
      val bobDirectoryName = perTestCaseName("bob")

      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3)

      // setup alice and bob
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createDirectoryEntry(
        aliceUserParty,
        aliceDirectoryClient,
        aliceDirectoryName,
        aliceWalletClient,
        cc,
      )
      createDirectoryEntry(bobUserParty, bobDirectoryClient, bobDirectoryName, bobWalletClient, cc)

      // transfer from bob to alice
      actAndCheck(
        "bob creates transfer offer",
        withFrontEnd("bob") { implicit webDriver =>
          browseToBobWallet(bobWalletClient.config.ledgerApiUser)
          createTransferOffer(aliceUserParty, transferAmount, expiryDays = 1)
        },
      )(
        "alice observes transfer offer",
        _ => aliceWalletClient.listTransferOffers() should have size 1,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        eventually() {
          findAll(className("transfer-offer")).toList should have size (1)
        }

        actAndCheck(
          "Alice accepts the offer", {
            click on className("transfer-offer-accept")
          },
        )(
          "Alice sees no more pending transfer offers",
          _ => {
            findAll(className("transfer-offer")).toList should have size (0)
            assertInRange(bobWalletClient.balance().unlockedQty, (BigDecimal(6), BigDecimal(7)))
            assertInRange(aliceWalletClient.balance().unlockedQty, (BigDecimal(12), BigDecimal(13)))
          },
        )
      }
    }

    "allow rejecting transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWalletClient, aliceValidatorBackend)
      val aliceDirectoryName = perTestCaseName("alice")

      val bobUserParty = setupForTestWithDirectory(bobWalletClient, bobValidatorBackend)
      val bobDirectoryName = perTestCaseName("bob")

      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3)

      // setup alice and bob
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createDirectoryEntry(
        aliceUserParty,
        aliceDirectoryClient,
        aliceDirectoryName,
        aliceWalletClient,
        cc,
      )
      createDirectoryEntry(bobUserParty, bobDirectoryClient, bobDirectoryName, bobWalletClient, cc)

      // transfer from bob to alice
      actAndCheck(
        "bob creates transfer offer",
        withFrontEnd("bob") { implicit webDriver =>
          browseToBobWallet(bobWalletClient.config.ledgerApiUser)
          createTransferOffer(aliceUserParty, transferAmount, expiryDays = 1)
        },
      )(
        "alice observes transfer offer",
        _ => aliceWalletClient.listTransferOffers() should have size 1,
      )

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
            assertInRange(bobWalletClient.balance().unlockedQty, (BigDecimal(9), BigDecimal(10)))
            assertInRange(aliceWalletClient.balance().unlockedQty, (BigDecimal(9), BigDecimal(10)))
          },
        )
      }
    }
  }
}
