package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  SpliceUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.console.WalletAppClientReference

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.UUID
import BaseWalletTransfersFrontendIntegrationTest.*

abstract class BaseWalletTransfersFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice", "bob")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  private val amuletPrice = 2
  override def walletAmuletPrice = SpliceUtil.damlDecimal(amuletPrice.toDouble)
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAmuletPrice(amuletPrice)
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  protected def createTransferOfferViaBackend(walletClient: WalletAppClientReference)(
      receiver: PartyId,
      amount: BigDecimal,
      description: String,
      expiry: CantonTimestamp,
      trackingId: String,
  ): Unit
  protected def listTransferOffersViaBackend(
      walletClient: WalletAppClientReference
  ): Seq[TransferOfferInBackend]
  protected def shouldDisableTokenStandardSwitch: Boolean

  "A wallet UI" should {

    "create a p2p transfer" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceAnsName = perTestCaseName("alice")

      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val bobAnsName = perTestCaseName("bob")

      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3.5)
      val expiryDays = 10

      // setup alice and bob
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createAnsEntry(
        aliceAnsExternalClient,
        aliceAnsName,
        aliceWalletClient,
        cc,
      )
      createAnsEntry(bobAnsExternalClient, bobAnsName, bobWalletClient, cc)

      listTransferOffersViaBackend(bobWalletClient) shouldBe empty

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        actAndCheck(
          "alice creates transfer offer", {
            createTransferOffer(
              bobUserParty,
              transferAmount,
              expiryDays,
              shouldDisableTokenStandardSwitch = shouldDisableTokenStandardSwitch,
            )
          },
        )(
          "alice is redirected to /transactions & bob observes transfer offer",
          _ => {
            currentUrl should endWith("/transactions")

            val offers = listTransferOffersViaBackend(bobWalletClient)
            offers should have size 1
            val transfer = offers.head
            val tenDaysFromNow = Instant.now().plus(expiryDays.toLong, ChronoUnit.DAYS)
            val timeDiff = ChronoUnit.MINUTES.between(transfer.expiry, tenDaysFromNow)

            transfer.amount.floatValue shouldBe transferAmount.floatValue
            transfer.description shouldBe "by party ID"
            transfer.sender shouldBe aliceUserParty
            transfer.receiver shouldBe bobUserParty

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
              ) should matchText(expectedAns(aliceUserParty, aliceAnsName))

              offerCard
                .childElement(className("transfer-offer-amulet-amount"))
                .text should matchText(
                s"+ $transferAmount $amuletNameAcronym"
              )

              offerCard
                .childElement(className("transfer-offer-usd-amount-rate"))
                .text should matchText(
                s"7 USD @ ${BigDecimal(1) / amuletPrice} $amuletNameAcronym/USD"
              )
            }
          },
        )
      }
    }

    "show a list of transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceAnsName = perTestCaseName("alice")

      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val bobAnsName = perTestCaseName("bob")
      val bobAnsDisplay = expectedAns(bobUserParty, bobAnsName)

      val transferExpiry = CantonTimestamp.now().plusSeconds(100)

      val expectedExpiry =
        DateTimeFormatter
          .ofPattern("yyyy-MM-dd HH:mm")
          .format(LocalDateTime.ofInstant(transferExpiry.toInstant, ZoneOffset.UTC))

      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createAnsEntry(
        aliceAnsExternalClient,
        aliceAnsName,
        aliceWalletClient,
      )
      createAnsEntry(bobAnsExternalClient, bobAnsName, bobWalletClient)

      actAndCheck(
        " Bob creates transfer offer to alice",
        createTransferOfferViaBackend(bobWalletClient)(
          aliceUserParty,
          BigDecimal(1),
          "Bob transfer to Alice",
          transferExpiry,
          UUID.randomUUID().toString,
        ),
      )(
        "alice observes transfer offer",
        _ => listTransferOffersViaBackend(aliceWalletClient) should have size 1,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        eventually() {
          val offerCards = findAll(className("transfer-offer")).toList

          offerCards should have size (1)

          inside(offerCards) { case Seq(offerCard) =>
            seleniumText(
              offerCard.childElement(className("transfer-offer-sender"))
            ) should matchText(bobAnsDisplay)

            offerCard.childElement(className("transfer-offer-expiry")).text should matchText(
              s"Expires $expectedExpiry"
            )

            offerCard.childElement(className("transfer-offer-amulet-amount")).text should matchText(
              s"+ 1 $amuletNameAcronym"
            )

            offerCard
              .childElement(className("transfer-offer-usd-amount-rate"))
              .text should matchText(
              s"2 USD @ 0.5 $amuletNameAcronym/USD"
            )
          }
        }
      }
    }

    "not show transfer offers I created" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceAnsName = perTestCaseName("alice")

      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val bobAnsName = perTestCaseName("bob")

      val transferExpiry = CantonTimestamp.now().plusSeconds(100)

      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createAnsEntry(
        aliceAnsExternalClient,
        aliceAnsName,
        aliceWalletClient,
      )
      createAnsEntry(bobAnsExternalClient, bobAnsName, bobWalletClient)

      actAndCheck(
        "Alice creates transfer offer to bob",
        createTransferOfferViaBackend(aliceWalletClient)(
          bobUserParty,
          BigDecimal(1),
          "Alice transfer to Bob",
          transferExpiry,
          UUID.randomUUID().toString,
        ),
      )(
        "alice has an outgoing transfer offer",
        _ => listTransferOffersViaBackend(aliceWalletClient) should have size 1,
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
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceAnsName = perTestCaseName("alice")

      val bobAnsName = perTestCaseName("bob")

      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3)

      // setup alice and bob
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createAnsEntry(
        aliceAnsExternalClient,
        aliceAnsName,
        aliceWalletClient,
        cc * amuletPrice,
      )
      createAnsEntry(bobAnsExternalClient, bobAnsName, bobWalletClient, cc * amuletPrice)

      // transfer from bob to alice
      actAndCheck(
        "bob creates transfer offer",
        withFrontEnd("bob") { implicit webDriver =>
          browseToBobWallet(bobWalletClient.config.ledgerApiUser)
          createTransferOffer(
            aliceUserParty,
            transferAmount,
            expiryDays = 1,
            shouldDisableTokenStandardSwitch = shouldDisableTokenStandardSwitch,
          )
        },
      )(
        "alice observes transfer offer",
        _ => listTransferOffersViaBackend(aliceWalletClient) should have size 1,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        eventually() {
          findAll(className("transfer-offer")).toList should have size (1)
        }

        actAndCheck(
          "Alice accepts the offer", {
            eventuallyClickOn(className("transfer-offer-accept"))
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
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceAnsName = perTestCaseName("alice")

      val bobAnsName = perTestCaseName("bob")

      val cc = BigDecimal(10)
      val transferAmount = BigDecimal(3)

      // setup alice and bob
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createAnsEntry(
        aliceAnsExternalClient,
        aliceAnsName,
        aliceWalletClient,
        cc * amuletPrice,
      )
      createAnsEntry(bobAnsExternalClient, bobAnsName, bobWalletClient, cc * amuletPrice)

      // transfer from bob to alice
      actAndCheck(
        "bob creates transfer offer",
        withFrontEnd("bob") { implicit webDriver =>
          browseToBobWallet(bobWalletClient.config.ledgerApiUser)
          createTransferOffer(
            aliceUserParty,
            transferAmount,
            expiryDays = 1,
            shouldDisableTokenStandardSwitch = shouldDisableTokenStandardSwitch,
          )
        },
      )(
        "alice observes transfer offer",
        _ => listTransferOffersViaBackend(aliceWalletClient) should have size 1,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        eventually() {
          findAll(className("transfer-offer")) should have size 1
        }

        actAndCheck(
          "Alice rejects the offer", {
            eventuallyClickOn(className("transfer-offer-reject"))
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

object BaseWalletTransfersFrontendIntegrationTest {
  case class TransferOfferInBackend(
      sender: PartyId,
      receiver: PartyId,
      description: String,
      amount: BigDecimal,
      expiry: Instant,
  )
}

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9
class WalletTransferOffersFrontendIntegrationTest
    extends BaseWalletTransfersFrontendIntegrationTest {
  override protected def createTransferOfferViaBackend(walletClient: WalletAppClientReference)(
      receiver: PartyId,
      amount: BigDecimal,
      description: String,
      expiry: CantonTimestamp,
      trackingId: String,
  ): Unit = walletClient.createTransferOffer(receiver, amount, description, expiry, trackingId)

  override protected def listTransferOffersViaBackend(
      walletClient: WalletAppClientReference
  ): Seq[TransferOfferInBackend] = walletClient.listTransferOffers().map { contract =>
    val transfer = contract.payload
    TransferOfferInBackend(
      sender = PartyId.tryFromProtoPrimitive(transfer.sender),
      receiver = PartyId.tryFromProtoPrimitive(transfer.receiver),
      description = transfer.description,
      amount = transfer.amount.amount,
      expiry = transfer.expiresAt,
    )
  }

  override protected def shouldDisableTokenStandardSwitch: Boolean = true
}

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9
class TokenStandardWalletTransfersFrontendIntegrationTest
    extends BaseWalletTransfersFrontendIntegrationTest {
  override protected def createTransferOfferViaBackend(walletClient: WalletAppClientReference)(
      receiver: PartyId,
      amount: BigDecimal,
      description: String,
      expiry: CantonTimestamp,
      trackingId: String,
  ): Unit =
    walletClient.createTokenStandardTransfer(receiver, amount, description, expiry, trackingId)

  override protected def listTransferOffersViaBackend(
      walletClient: WalletAppClientReference
  ): Seq[TransferOfferInBackend] =
    walletClient.listTokenStandardTransfers().map { contract =>
      val transfer = contract.payload.transfer
      TransferOfferInBackend(
        sender = PartyId.tryFromProtoPrimitive(transfer.sender),
        receiver = PartyId.tryFromProtoPrimitive(transfer.receiver),
        description =
          transfer.meta.values.getOrDefault("splice.lfdecentralizedtrust.org/reason", ""),
        amount = transfer.amount,
        expiry = transfer.executeBefore,
      )
    }

  override protected def shouldDisableTokenStandardSwitch: Boolean = false
}
