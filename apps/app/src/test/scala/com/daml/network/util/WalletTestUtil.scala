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

  /** Collects an accepted app payment request without doing anything useful in return. */
  def collectAcceptedAppPaymentRequest(
      remoteParticipant: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      acceptedPayment: paymentCodegen.AcceptedAppPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    remoteParticipant.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = acceptedPayment.exerciseAcceptedAppPayment_Collect(
        appTc
      ),
      domainId = domainId,
      disclosedContracts =
        Seq(tc.coinRules.toDisclosedContract, tc.latestOpenMiningRound.toDisclosedContract),
    )
  }

  /** Rejects an accepted app payment request. */
  def rejectAcceptedAppPaymentRequest(
      remoteParticipant: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      acceptedPayment: paymentCodegen.AcceptedAppPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    remoteParticipant.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = acceptedPayment.exerciseAcceptedAppPayment_Reject(appTc),
      domainId = domainId,
      disclosedContracts =
        Seq(tc.coinRules.toDisclosedContract, tc.latestOpenMiningRound.toDisclosedContract),
    )
  }

  /** Collects an accepted subscription payment request without doing anything useful in return. */
  def collectAcceptedSubscriptionRequest(
      remoteParticipant: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      sender: PartyId,
      acceptedPayment: subsCodegen.SubscriptionInitialPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    remoteParticipant.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty, sender),
      readAs = Seq(),
      update = acceptedPayment.exerciseSubscriptionInitialPayment_Collect(
        appTc
      ),
      domainId = domainId,
      disclosedContracts =
        Seq(tc.coinRules.toDisclosedContract, tc.latestOpenMiningRound.toDisclosedContract),
    )
  }

  def rejectAcceptedSubscriptionRequest(
      remoteParticipant: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      acceptedPayment: subsCodegen.SubscriptionInitialPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    remoteParticipant.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = acceptedPayment.exerciseSubscriptionInitialPayment_Reject(
        appTc
      ),
      domainId = domainId,
      disclosedContracts =
        Seq(tc.coinRules.toDisclosedContract, tc.latestOpenMiningRound.toDisclosedContract),
    )
  }

  /** Collects an accepted app payment request without doing anything useful in return. */
  def collectSubscriptionPayment(
      remoteParticipant: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      senderParty: PartyId,
      payment: subsCodegen.SubscriptionPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    remoteParticipant.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty, senderParty),
      readAs = Seq(),
      update = payment.exerciseSubscriptionPayment_Collect(
        appTc
      ),
      domainId = domainId,
      disclosedContracts =
        Seq(tc.coinRules.toDisclosedContract, tc.latestOpenMiningRound.toDisclosedContract),
    )
  }

  def rejectSubscriptionPayment(
      remoteParticipant: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      payment: subsCodegen.SubscriptionPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    remoteParticipant.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = payment.exerciseSubscriptionPayment_Reject(
        appTc
      ),
      domainId = domainId,
      disclosedContracts =
        Seq(tc.coinRules.toDisclosedContract, tc.latestOpenMiningRound.toDisclosedContract),
    )
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
      remoteParticipantWithAdminToken: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      domainId: Option[DomainId] = None,
  )(implicit env: CoinTestConsoleEnvironment): testWalletCodegen.TestDeliveryOffer.ContractId = {
    val deliveryOffer = new testWalletCodegen.TestDeliveryOffer(
      scan.getSvcPartyId().toProtoPrimitive,
      userParty.toProtoPrimitive,
      "description",
    )
    clue("Create delivery offer") {
      val result = remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = deliveryOffer.create,
        domainId = domainId,
      )
      testWalletCodegen.TestDeliveryOffer.COMPANION.toContractId(result.contractId)
    }
  }

  def paymentAmount(
      amount: BigDecimal,
      currency: paymentCodegen.Currency,
  ) =
    new paymentCodegen.PaymentAmount(
      amount.bigDecimal,
      currency,
    )

  def receiverAmount(
      receiverParty: PartyId,
      amount: BigDecimal,
      currency: paymentCodegen.Currency,
  ) =
    new paymentCodegen.ReceiverAmount(
      receiverParty.toProtoPrimitive,
      new paymentCodegen.PaymentAmount(
        amount.bigDecimal,
        currency,
      ),
    )

  def createPaymentRequest(
      remoteParticipantWithAdminToken: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      receiverAmounts: Seq[paymentCodegen.ReceiverAmount],
      expirationTime: Duration = Duration.ofMinutes(5),
      domainId: Option[DomainId] = None,
  )(implicit env: CoinTestConsoleEnvironment): (
      testWalletCodegen.TestDeliveryOffer.ContractId,
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
  ) = {
    val deliveryOfferId =
      createTestDeliveryOffer(remoteParticipantWithAdminToken, userId, userParty)

    val now = env.environment.clock.now

    val paymentRequest = new paymentCodegen.AppPaymentRequest(
      userParty.toProtoPrimitive,
      receiverAmounts.asJava,
      userParty.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      now.plus(expirationTime).toInstant,
      deliveryOfferId.toInterface(paymentCodegen.DeliveryOffer.INTERFACE),
    )

    val requestCid = clue("Create a payment request") {
      val result = remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = paymentRequest.create,
        domainId = domainId,
      )
      paymentCodegen.AppPaymentRequest.COMPANION.toContractId(result.contractId)
    }

    (deliveryOfferId, requestCid, paymentRequest)
  }

  def createSelfPaymentRequest(
      remoteParticipantWithAdminToken: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      amount: BigDecimal = defaultPaymentAmount.amount,
      currency: paymentCodegen.Currency = defaultPaymentAmount.currency,
      expirationTime: Duration = Duration.ofMinutes(5),
      domainId: Option[DomainId] = None,
  )(implicit env: CoinTestConsoleEnvironment): (
      testWalletCodegen.TestDeliveryOffer.ContractId,
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
  ) = {
    val receiverAmounts = Seq(
      receiverAmount(userParty, amount, currency)
    )

    createPaymentRequest(
      remoteParticipantWithAdminToken,
      userId,
      userParty,
      receiverAmounts,
      expirationTime,
      domainId,
    )
  }

  private val defaultSubscriptionAmount = new paymentCodegen.PaymentAmount(
    BigDecimal(10).bigDecimal.setScale(10),
    paymentCodegen.Currency.CC,
  )
  private val defaultSubscriptionInterval = Duration.ofMinutes(10)
  private val defaultSubscriptionDuration = Duration.ofMinutes(60)
  private val defaultPaymentAmount = new paymentCodegen.PaymentAmount(
    BigDecimal(10).bigDecimal.setScale(10),
    paymentCodegen.Currency.CC,
  )

  protected def createSubscriptionContext(
      remoteParticipantWithAdminToken: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      serviceParty: PartyId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): testSubsCodegen.TestSubscriptionContext.ContractId = {
    val context = new testSubsCodegen.TestSubscriptionContext(
      scan.getSvcPartyId().toProtoPrimitive,
      userParty.toProtoPrimitive,
      serviceParty.toProtoPrimitive,
      "description",
    )
    clue("Create a subscription context") {
      val result = remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty, serviceParty).distinct,
        readAs = Seq.empty,
        update = context.create,
        domainId = domainId,
      )
      testSubsCodegen.TestSubscriptionContext.COMPANION.toContractId(result.contractId)
    }
  }

  private def createSubscriptionData(
      contextId: testSubsCodegen.TestSubscriptionContext.ContractId,
      userParty: PartyId,
      receiverParty: PartyId,
      providerParty: PartyId,
      paymentInterval: Duration,
      paymentDuration: Duration,
      amount: paymentCodegen.PaymentAmount,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val subscription = new subsCodegen.Subscription(
      userParty.toProtoPrimitive,
      receiverParty.toProtoPrimitive,
      providerParty.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      contextId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
    )
    val payData = new subsCodegen.SubscriptionPayData(
      amount,
      new RelTime(paymentInterval.toMillis * 1000L),
      new RelTime(paymentDuration.toMillis * 1000L),
    )
    (subscription, payData)
  }

  private def createSelfSubscriptionData(
      contextId: testSubsCodegen.TestSubscriptionContext.ContractId,
      userParty: PartyId,
      paymentInterval: Duration,
      paymentDuration: Duration,
      amount: paymentCodegen.PaymentAmount,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    createSubscriptionData(
      contextId,
      userParty,
      userParty,
      userParty,
      paymentInterval,
      paymentDuration,
      amount,
    )
  }

  /** Note: all of the sender, receiver, and provider parties must be on the same participant */
  protected def createSubscriptionRequest(
      remoteParticipantWithAdminToken: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      receiverParty: PartyId,
      providerParty: PartyId,
      amount: paymentCodegen.PaymentAmount = defaultPaymentAmount,
      paymentInterval: Duration = defaultSubscriptionInterval,
      paymentDuration: Duration = defaultSubscriptionDuration,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val contextId =
      createSubscriptionContext(
        remoteParticipantWithAdminToken,
        userId,
        userParty,
        receiverParty,
        domainId,
      )
    val (subscription, payData) =
      createSubscriptionData(
        contextId,
        userParty,
        receiverParty,
        providerParty,
        paymentInterval,
        paymentDuration,
        amount,
      )
    val subscriptionRequest = new subsCodegen.SubscriptionRequest(
      subscription,
      payData,
    )
    clue("Create subscription request") {
      remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty, receiverParty, providerParty).distinct,
        readAs = Seq.empty,
        update = subscriptionRequest.create,
        domainId = domainId,
      )
    }
    subscriptionRequest
  }

  protected def createSelfSubscriptionRequest(
      remoteParticipantWithAdminToken: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      amount: paymentCodegen.PaymentAmount = defaultPaymentAmount,
      paymentInterval: Duration = defaultSubscriptionInterval,
      paymentDuration: Duration = defaultSubscriptionDuration,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val contextId =
      createSubscriptionContext(
        remoteParticipantWithAdminToken,
        userId,
        userParty,
        userParty,
        domainId,
      )
    val (subscription, payData) =
      createSelfSubscriptionData(contextId, userParty, paymentInterval, paymentDuration, amount)
    val subscriptionRequest = new subsCodegen.SubscriptionRequest(
      subscription,
      payData,
    )
    clue("Create subscription request") {
      remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = subscriptionRequest.create,
        domainId = domainId,
      )
    }
    subscriptionRequest
  }

  protected def createSelfSubscription(
      remoteParticipantWithAdminToken: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
      amount: paymentCodegen.PaymentAmount = defaultSubscriptionAmount,
      paymentInterval: Duration = defaultSubscriptionInterval,
      paymentDuration: Duration = defaultSubscriptionDuration,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val contextId =
      createSubscriptionContext(
        remoteParticipantWithAdminToken,
        userId,
        userParty,
        userParty,
        domainId,
      )
    val (subscriptionData, payData) =
      createSelfSubscriptionData(contextId, userParty, paymentInterval, paymentDuration, amount)
    val subscriptionId = clue("Create a subscription") {
      val subscription = new subsCodegen.Subscription(
        userParty.toProtoPrimitive,
        userParty.toProtoPrimitive,
        userParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        contextId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
      )
      val result = remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = subscription.create,
        domainId = domainId,
      )
      subsCodegen.Subscription.COMPANION.toContractId(result.contractId)
    }
    val nextPaymentDueAt =
      env.environment.clock.now.addMicros(payData.paymentInterval.microseconds).toInstant
    clue("Create a subscription idle state") {
      val state = new subsCodegen.SubscriptionIdleState(
        subscriptionId,
        subscriptionData,
        payData,
        nextPaymentDueAt,
      )
      remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = state.create,
        domainId = domainId,
      )
    }
  }
}
