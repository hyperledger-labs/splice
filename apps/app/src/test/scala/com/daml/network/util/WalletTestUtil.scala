package com.daml.network.util

import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.wallet.{
  payment as paymentCodegen,
  subscriptions as subsCodegen,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.*
import com.daml.network.integration.tests.CoinTests.{CoinTestCommon, CoinTestConsoleEnvironment}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

trait WalletTestUtil extends CoinTestCommon with CnsTestUtil {
  this: CommonCoinAppInstanceReferences =>

  val exactly = (x: BigDecimal) => (x, x)

  /** @param expectedAmountRanges : lower and upper bounds for coins sorted by their initial amount in ascending order. */
  def checkWallet(
      walletParty: PartyId,
      wallet: WalletAppClientReference,
      expectedAmountRanges: Seq[(BigDecimal, BigDecimal)],
  ): Unit = clue(s"checking wallet with $expectedAmountRanges") {
    eventually(10.seconds, 500.millis) {
      val coins =
        wallet.list().coins.sortBy(coin => coin.contract.payload.amount.initialAmount)
      coins should have size (expectedAmountRanges.size.toLong)
      coins
        .zip(expectedAmountRanges)
        .foreach { case (coin, amountBounds) =>
          coin.contract.payload.owner shouldBe walletParty.toPrim
          val coinAmount =
            coin.contract.payload.amount
          assertInRange(coinAmount.initialAmount, amountBounds)
          coinAmount.ratePerRound shouldBe
            CoinUtil.defaultHoldingFee
        }
    }
  }

  def checkBalance(
      wallet: WalletAppClientReference,
      expectedRound: Long,
      expectedUnlockedQtyRange: (BigDecimal, BigDecimal),
      expectedLockedQtyRange: (BigDecimal, BigDecimal),
      expectedHoldingFeeRange: (BigDecimal, BigDecimal),
  ): Unit = clue(s"Checking balance in round $expectedRound") {
    eventually() {
      val balance = wallet.balance()
      balance.round shouldBe expectedRound
      assertInRange(balance.unlockedQty, expectedUnlockedQtyRange)
      assertInRange(balance.lockedQty, expectedLockedQtyRange)
      assertInRange(balance.holdingFees, expectedHoldingFeeRange)
    }
  }

  /** Onboards the daml user associated with the given wallet app user reference
    * onto the given validator, and waits until the wallet is usable for that user
    */
  def onboardWalletUser(
      walletAppClient: WalletAppClientReference,
      validator: ValidatorAppReference,
  ): PartyId = {
    val ledgerApiUser = walletAppClient.config.ledgerApiUser

    clue(s"Onboard $ledgerApiUser on ${validator.name}") {
      val party = validator.onboardUser(ledgerApiUser)
      waitForWalletUser(walletAppClient)
      party
    }
  }

  /** The wallet is not immediately usable by an onboarded user, specifically, the wallet
    * app backend needs to ingest the wallet install contract first. This function waits for
    * that to complete.
    */

  def waitForWalletUser(
      walletAppClient: WalletAppClientReference
  ) = {
    eventually() {
      walletAppClient.userStatus().userOnboarded shouldBe true
    }
  }

  def onboardAliceAndBob()(implicit
      env: CoinTestConsoleEnvironment
  ): (PartyId, PartyId) = {
    val alice = onboardWalletUser(aliceWallet, aliceValidator)
    val bob = onboardWalletUser(bobWallet, bobValidator)
    (alice, bob)
  }

  def p2pTransfer(
      senderWallet: WalletAppClientReference,
      receiverWallet: WalletAppClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      // We allow disabling this since nested log suppression doesn't work
      retryAcceptance: Boolean = true,
  ) = {
    val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(1))
    val transferOfferId =
      senderWallet.createTransferOffer(
        receiver,
        amount,
        "test transfer",
        expiration,
        idempotencyKey = UUID.randomUUID.toString,
      )
    eventually() {
      forExactly(1, receiverWallet.listTransferOffers())(offer =>
        offer.contractId shouldBe transferOfferId
      )
    }
    // TODO (#2728) Remove this hacky retry against contract not found errors.
    // This is only necessary because the update service does not synchronize properly.
    if (retryAcceptance)
      eventually() {
        loggerFactory.assertThrowsAndLogs[CommandFailure](
          receiverWallet.acceptTransferOffer(transferOfferId),
          _.errorMessage should include("CONTRACT_NOT_FOUND"),
        )
      }
    else {
      receiverWallet.acceptTransferOffer(transferOfferId)
    }
    // note that something like `receiverWallet.listAcceptedTransferOffers() should have size 1`
    // is potentially racy (possible to circumvent this by being clever, but we chose the simple solution for now)
  }

  def createSelfPaymentRequest(
      remoteParticipant: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): (
      testWalletCodegen.TestDeliveryOffer.ContractId,
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
  ) = {
    val now = env.environment.clock.now
    val referenceId = clue(s"Create test delivery offer for $userParty") {
      val result =
        remoteParticipant.ledger_api_extensions.commands.submitWithResult(
          userId = userId,
          actAs = Seq(userParty),
          readAs = Seq.empty,
          update = new testWalletCodegen.TestDeliveryOffer(
            scan.getSvcPartyId().toProtoPrimitive,
            userParty.toProtoPrimitive,
            "description",
          ).create,
          domainId = domainId,
        )
      testWalletCodegen.TestDeliveryOffer.COMPANION.toContractId(result.contractId)
    }

    val (reqCid, reqC) = clue(s"Create payment request for $userParty to self") {
      val reqC = new paymentCodegen.AppPaymentRequest(
        userParty.toProtoPrimitive,
        Seq(
          new paymentCodegen.ReceiverAmount(
            userParty.toProtoPrimitive,
            new paymentCodegen.PaymentAmount(
              BigDecimal(10).bigDecimal.setScale(10),
              paymentCodegen.Currency.CC,
            ),
          )
        ).asJava,
        userParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        now.plus(Duration.ofMinutes(1)).toInstant,
        referenceId.toInterface(paymentCodegen.DeliveryOffer.INTERFACE),
      )
      val result = remoteParticipant.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = reqC.create,
        domainId = domainId,
      )
      val cid = paymentCodegen.AppPaymentRequest.COMPANION.toContractId(result.contractId)
      (cid, reqC)
    }

    (referenceId, reqCid, reqC)
  }

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"

  protected def setupForTestWithDirectory(
      walletClient: WalletAppClientReference,
      validator: ValidatorAppBackendReference,
  ) = {
    validator.remoteParticipant.dars.upload(directoryDarPath)
    onboardWalletUser(walletClient, validator)
  }

  protected def createDirectoryEntryForDirectoryItself(implicit
      env: CoinTestConsoleEnvironment
  ): String = {
    val dirEntryName = "directory.cns"
    val dirParty = directory.getProviderPartyId()
    directory.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
      actAs = Seq(dirParty),
      commands = new dirCodegen.DirectoryEntry(
        dirParty.toProtoPrimitive,
        dirParty.toProtoPrimitive,
        dirEntryName,
        Instant.now().plus(90, ChronoUnit.DAYS),
      ).create.commands.asScala.toSeq,
      optTimeout = None,
    )
    expectedCns(dirParty, dirEntryName)
  }

  protected def createDirectoryEntry(
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      dirEntry: String,
      wallet: WalletAppClientReference,
  ) = {
    requestDirectoryEntry(userParty, directory, dirEntry)
    wallet.tap(5.0)
    eventually() {
      wallet.listSubscriptionRequests() should have length 1
    }
    wallet.acceptSubscriptionRequest(
      wallet.listSubscriptionRequests().head.contractId
    )
  }

  protected def requestDirectoryEntry(
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      dirEntry: String,
  ) = {
    // Whitelist the directory service on alice's validator
    directory.requestDirectoryInstall()
    eventually() {
      directory.ledgerApi.ledger_api_extensions.acs
        .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(userParty)
    }
    directory.requestDirectoryEntry(dirEntry)
  }

  def createTestDeliveryOffer(
      aliceUserParty: PartyId
  )(implicit env: CoinTestConsoleEnvironment) = {
    val deliveryOffer = new testWalletCodegen.TestDeliveryOffer(
      scan.getSvcPartyId().toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      "description",
    )
    clue("Create delivery offer") {
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = deliveryOffer.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .awaitJava(testWalletCodegen.TestDeliveryOffer.COMPANION)(
          aliceUserParty,
          _.data == deliveryOffer,
        )
        .id
    }
  }

  def receiverAmount(
      receiverParty: PartyId,
      amount: Int,
      currency: paymentCodegen.Currency,
  ) =
    new paymentCodegen.ReceiverAmount(
      receiverParty.toProtoPrimitive,
      new paymentCodegen.PaymentAmount(
        BigDecimal(amount).bigDecimal,
        currency,
      ),
    )

  def createPaymentRequest(
      aliceUserParty: PartyId,
      receiverAmounts: Seq[paymentCodegen.ReceiverAmount],
  )(implicit env: CoinTestConsoleEnvironment) = {
    val deliveryOfferId = createTestDeliveryOffer(aliceUserParty)

    clue("Create a payment request") {
      val paymentRequest = new paymentCodegen.AppPaymentRequest(
        aliceUserParty.toProtoPrimitive,
        receiverAmounts.asJava,
        aliceUserParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        Instant.now().plus(5, ChronoUnit.MINUTES), // expires in 5 min
        deliveryOfferId.toInterface(paymentCodegen.DeliveryOffer.INTERFACE),
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = paymentRequest.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .awaitJava(paymentCodegen.AppPaymentRequest.COMPANION)(aliceUserParty)
        .id
    }
  }

  def createSelfPaymentRequest(
      aliceUserParty: PartyId,
      amount: Int,
      currency: paymentCodegen.Currency,
  )(implicit env: CoinTestConsoleEnvironment) = {
    val receiverAmounts = Seq(
      receiverAmount(aliceUserParty, amount, currency)
    )

    createPaymentRequest(aliceUserParty, receiverAmounts)
  }

  private val defaultSubscriptionAmount = new paymentCodegen.PaymentAmount(
    BigDecimal(10).bigDecimal.setScale(10),
    paymentCodegen.Currency.CC,
  )

  protected def createSelfSubscriptionContext(aliceUserParty: PartyId)(implicit
      env: CoinTestConsoleEnvironment
  ): testSubsCodegen.TestSubscriptionContext.ContractId = {
    val context = new testSubsCodegen.TestSubscriptionContext(
      scan.getSvcPartyId().toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      "description",
    )
    clue("Create a subscription context") {
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = context.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .awaitJava(testSubsCodegen.TestSubscriptionContext.COMPANION)(
          aliceUserParty,
          _.data == context,
        )
        .id
    }
  }

  private def createSelfSubscriptionData(
      contextId: testSubsCodegen.TestSubscriptionContext.ContractId,
      aliceUserParty: PartyId,
      nextPaymentDueAt: Instant,
      amount: paymentCodegen.PaymentAmount,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val subscription = new subsCodegen.Subscription(
      aliceUserParty.toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      contextId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
    )
    val payData = new subsCodegen.SubscriptionPayData(
      amount,
      new RelTime(60 * 60 * 1000000L),
      new RelTime(10 * 60 * 1000000L),
    )
    (subscription, payData)
  }

  protected def createSelfSubscriptionRequest(
      aliceUserParty: PartyId,
      nextPaymentDueAt: Instant,
      amount: paymentCodegen.PaymentAmount,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val contextId = createSelfSubscriptionContext(aliceUserParty)
    val (subscription, payData) =
      createSelfSubscriptionData(contextId, aliceUserParty, nextPaymentDueAt, amount)
    clue("Create subscription request") {
      val subscriptionRequest = new subsCodegen.SubscriptionRequest(
        subscription,
        payData,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = subscriptionRequest.create.commands.asScala.toSeq,
      )
    }
  }

  protected def createSelfSubscription(
      aliceUserParty: PartyId,
      nextPaymentDueAt: Instant,
      amount: paymentCodegen.PaymentAmount = defaultSubscriptionAmount,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val contextId = createSelfSubscriptionContext(aliceUserParty)
    val (subscriptionData, payData) =
      createSelfSubscriptionData(contextId, aliceUserParty, nextPaymentDueAt, amount)
    val subscriptionId = clue("Create a subscription") {
      val subscription = new subsCodegen.Subscription(
        aliceUserParty.toProtoPrimitive,
        aliceUserParty.toProtoPrimitive,
        aliceUserParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        contextId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = subscription.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .awaitJava(subsCodegen.Subscription.COMPANION)(aliceUserParty, _.data == subscription)
        .id
    }
    clue("Create a subscription idle state") {
      val state = new subsCodegen.SubscriptionIdleState(
        subscriptionId,
        subscriptionData,
        payData,
        nextPaymentDueAt,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = state.create.commands.asScala.toSeq,
      )
    }
  }
}
