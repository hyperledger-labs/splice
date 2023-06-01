package com.daml.network.util

import com.daml.ledger.javaapi.data.ExercisedEvent
import com.daml.network.codegen.java.cc.api.v1 as coinApiCodegen
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.fees as feesCodegen
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionInitialPayment
import com.daml.network.codegen.java.cn.wallet.{
  payment as paymentCodegen,
  subscriptions as subsCodegen,
  install as walletInstallCodegen,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{ValidatorAppBackendReference, *}
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.console.CommandExecutionFailedException
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

trait WalletTestUtil extends CNNodeTestCommon with CnsTestUtil {
  this: CommonCNNodeAppInstanceReferences =>

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
            CNNodeUtil.defaultHoldingFee
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

  def assertUserFullyOnboarded(
      walletAppClient: WalletAppClientReference,
      validatorAppBackend: ValidatorAppBackendReference,
  ): org.scalatest.Assertion = {
    val status = walletAppClient.userStatus()
    status.userOnboarded shouldBe true
    status.userWalletInstalled shouldBe true

    val endUserParty = validatorAppBackend.participantClientWithAdminToken.ledger_api.users
      .get(walletAppClient.config.ledgerApiUser)
      .primaryParty
      .value

    status.party shouldBe endUserParty.toProtoPrimitive

    // Validator user must have rights for the end user party
    val validatorRights =
      validatorAppBackend.participantClientWithAdminToken.ledger_api.users.rights
        .list(validatorAppBackend.config.ledgerApiUser)
    validatorRights.actAs should contain(endUserParty)

    // There should be ValidatorRight and WalletInstall contracts
    val ledgerApiEx = validatorAppBackend.participantClientWithAdminToken.ledger_api_extensions
    ledgerApiEx.acs.filterJava(coinCodegen.ValidatorRight.COMPANION)(
      endUserParty,
      c => c.data.user == endUserParty.toProtoPrimitive,
    ) should have size 1
    ledgerApiEx.acs.filterJava(walletInstallCodegen.WalletAppInstall.COMPANION)(
      endUserParty,
      c => c.data.endUserParty == endUserParty.toProtoPrimitive,
    ) should have size 1
  }

  def assertUserFullyOffboarded(
      walletAppClient: WalletAppClientReference,
      validatorAppBackend: ValidatorAppBackendReference,
  ): org.scalatest.Assertion = {
    // Wallet must report that user is not onboarded
    val status = walletAppClient.userStatus()
    status.userOnboarded shouldBe false
    status.userWalletInstalled shouldBe false

    val endUserParty = validatorAppBackend.participantClientWithAdminToken.ledger_api.users
      .get(walletAppClient.config.ledgerApiUser)
      .primaryParty
      .value

    // Validator user must not have any rights for the end user party
    val ledgerApi = validatorAppBackend.participantClientWithAdminToken.ledger_api
    val validatorRights = ledgerApi.users.rights
      .list(validatorAppBackend.config.ledgerApiUser)
    validatorRights.readAs should not contain endUserParty
    validatorRights.actAs should not contain endUserParty

    // All validator right and wallet install contracts must be gone
    val ledgerApiEx = validatorAppBackend.participantClientWithAdminToken.ledger_api_extensions
    ledgerApiEx.acs.filterJava(coinCodegen.ValidatorRight.COMPANION)(
      endUserParty,
      c => c.data.user == endUserParty.toProtoPrimitive,
    ) shouldBe empty
    ledgerApiEx.acs.filterJava(walletInstallCodegen.WalletAppInstall.COMPANION)(
      endUserParty,
      c => c.data.endUserParty == endUserParty.toProtoPrimitive,
    ) shouldBe empty
  }

  /** The wallet is not immediately usable by an onboarded user, specifically, the wallet
    * app backend needs to ingest the wallet install contract first. This function waits for
    * that to complete.
    */

  def waitForWalletUser(
      walletAppClient: WalletAppClientReference
  ) = {
    eventually() {
      val status = walletAppClient.userStatus()
      status.userOnboarded shouldBe true
      status.userWalletInstalled shouldBe true
    }
  }

  def onboardAliceAndBob()(implicit
      env: CNNodeTestConsoleEnvironment
  ): (PartyId, PartyId) = {
    val alice = onboardWalletUser(aliceWallet, aliceValidator)
    val bob = onboardWalletUser(bobWallet, bobValidator)
    (alice, bob)
  }

  def p2pTransfer(
      senderValidator: ValidatorAppBackendReference,
      senderWallet: WalletAppClientReference,
      receiverWallet: WalletAppClientReference,
      receiver: PartyId,
      amount: BigDecimal,
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
    val offsetBefore =
      senderValidator.participantClientWithAdminToken.ledger_api.transactions.end()
    val acceptedCid = receiverWallet.acceptTransferOffer(transferOfferId)

    val senderParty = PartyId.tryFromProtoPrimitive(senderWallet.userStatus().party)

    eventually() {
      val offset = senderValidator.participantClientWithAdminToken.ledger_api.transactions.end()
      val transactions =
        senderValidator.participantClientWithAdminToken.ledger_api_extensions.transactions
          .treesJava(
            Set(senderParty),
            completeAfter = Int.MaxValue,
            beginOffset = offsetBefore,
            endOffset = Some(offset),
          )
      forExactly(1, transactions) { transaction =>
        forExactly(1, transaction.getEventsById.asScala.values)(
          inside(_) { case event: ExercisedEvent =>
            event.getContractId shouldBe acceptedCid.contractId
            event.getChoice shouldBe "AcceptedTransferOffer_Complete"
          }
        )
      }
    }
  }

  /** Collects an accepted app payment request without doing anything useful in return. */
  def collectAcceptedAppPaymentRequest(
      participantClient: CNParticipantClientReference,
      userId: String,
      signatories: Seq[PartyId],
      acceptedPayment: paymentCodegen.AcceptedAppPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = signatories,
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
      participantClient: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      acceptedPayment: paymentCodegen.AcceptedAppPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    participantClient.ledger_api_extensions.commands.submitWithResult(
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
      participantClient: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      sender: PartyId,
      acceptedPayment: subsCodegen.SubscriptionInitialPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    participantClient.ledger_api_extensions.commands
      .submitWithResult(
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
      .exerciseResult
  }

  def rejectAcceptedSubscriptionRequest(
      participantClient: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      acceptedPayment: subsCodegen.SubscriptionInitialPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    participantClient.ledger_api_extensions.commands.submitWithResult(
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
      participantClient: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      senderParty: PartyId,
      payment: subsCodegen.SubscriptionPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    participantClient.ledger_api_extensions.commands.submitWithResult(
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
      participantClient: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      payment: subsCodegen.SubscriptionPayment.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    participantClient.ledger_api_extensions.commands.submitWithResult(
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

  /** Expires a subscription that has not been paid in time. */
  def expireUnpaidSubscription(
      participantClient: CNParticipantClientReference,
      userId: String,
      actor: PartyId,
      subscriptionIdleState: subsCodegen.SubscriptionIdleState.ContractId,
      domainId: Option[DomainId] = None,
  ): Unit = {
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(actor),
      readAs = Seq(),
      update = subscriptionIdleState.exerciseSubscriptionIdleState_ExpireSubscription(
        actor.toProtoPrimitive
      ),
      domainId = domainId,
      disclosedContracts = Seq.empty,
    )
  }

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"

  protected def setupForTestWithDirectory(
      walletClient: WalletAppClientReference,
      validator: ValidatorAppBackendReference,
  ) = {
    validator.participantClient.upload_dar_unless_exists(directoryDarPath)
    onboardWalletUser(walletClient, validator)
  }

  protected def createDirectoryEntryForDirectoryItself(implicit
      env: CNNodeTestConsoleEnvironment
  ): String = {
    val dirEntryName = "directory.cns"
    val dirParty = directory.getProviderPartyId()
    directory.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
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
      directory: DirectoryAppClientReference,
      dirEntry: String,
      wallet: WalletAppClientReference,
      tapAmount: BigDecimal = 5.0,
  ): SubscriptionInitialPayment.ContractId = {
    requestDirectoryEntry(userParty, directory, dirEntry)
    wallet.tap(tapAmount)
    eventually() {
      wallet.listSubscriptionRequests() should have length 1
    }
    wallet.acceptSubscriptionRequest(
      wallet.listSubscriptionRequests().head.subscriptionRequest.contractId
    )
  }

  protected def requestDirectoryEntry(
      userParty: PartyId,
      directory: DirectoryAppClientReference,
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
      participantClientWithAdminToken: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      domainId: Option[DomainId] = None,
      description: String = "description",
  )(implicit env: CNNodeTestConsoleEnvironment): testWalletCodegen.TestDeliveryOffer.ContractId = {
    val deliveryOffer = new testWalletCodegen.TestDeliveryOffer(
      scan.getSvcPartyId().toProtoPrimitive,
      userParty.toProtoPrimitive,
      description,
    )
    clue("Create delivery offer") {
      val result = participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
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
      participantClientWithAdminToken: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      receiverAmounts: Seq[paymentCodegen.ReceiverAmount],
      expirationTime: Duration = Duration.ofMinutes(5),
      domainId: Option[DomainId] = None,
      description: String = "description",
  )(implicit env: CNNodeTestConsoleEnvironment): (
      testWalletCodegen.TestDeliveryOffer.ContractId,
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
  ) = {
    val deliveryOfferId =
      createTestDeliveryOffer(
        participantClientWithAdminToken,
        userId,
        userParty,
        description = description,
      )

    val now = env.environment.clock.now

    val paymentRequest = new paymentCodegen.AppPaymentRequest(
      userParty.toProtoPrimitive,
      receiverAmounts.asJava,
      userParty.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      now.plus(expirationTime).toInstant,
      deliveryOfferId.toInterface(paymentCodegen.DeliveryOffer.INTERFACE),
    )

    val signatories =
      Seq(userParty) ++ receiverAmounts.map(a => PartyId.tryFromProtoPrimitive(a.receiver))

    val requestCid = clue("Create a payment request") {
      val result = participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = signatories.distinct,
        readAs = Seq.empty,
        update = paymentRequest.create,
        domainId = domainId,
      )
      paymentCodegen.AppPaymentRequest.COMPANION.toContractId(result.contractId)
    }

    (deliveryOfferId, requestCid, paymentRequest)
  }

  def createSelfPaymentRequest(
      participantClientWithAdminToken: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      amount: BigDecimal = defaultPaymentAmount.amount,
      currency: paymentCodegen.Currency = defaultPaymentAmount.currency,
      expirationTime: Duration = Duration.ofMinutes(5),
      domainId: Option[DomainId] = None,
      description: String = "description",
  )(implicit env: CNNodeTestConsoleEnvironment): (
      testWalletCodegen.TestDeliveryOffer.ContractId,
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
  ) = {
    val receiverAmounts = Seq(
      receiverAmount(userParty, amount, currency)
    )

    createPaymentRequest(
      participantClientWithAdminToken,
      userId,
      userParty,
      receiverAmounts,
      expirationTime,
      domainId,
      description,
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
      participantClientWithAdminToken: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      serviceParty: PartyId,
      domainId: Option[DomainId] = None,
      description: String = "description",
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): testSubsCodegen.TestSubscriptionContext.ContractId = {
    val context = new testSubsCodegen.TestSubscriptionContext(
      scan.getSvcPartyId().toProtoPrimitive,
      userParty.toProtoPrimitive,
      serviceParty.toProtoPrimitive,
      description,
    )
    clue("Create a subscription context") {
      val result = participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
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
      env: CNNodeTestConsoleEnvironment
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
      env: CNNodeTestConsoleEnvironment
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
      participantClientWithAdminToken: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      receiverParty: PartyId,
      providerParty: PartyId,
      amount: paymentCodegen.PaymentAmount = defaultPaymentAmount,
      paymentInterval: Duration = defaultSubscriptionInterval,
      paymentDuration: Duration = defaultSubscriptionDuration,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val contextId =
      createSubscriptionContext(
        participantClientWithAdminToken,
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
      participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
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
      participantClientWithAdminToken: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      amount: paymentCodegen.PaymentAmount = defaultPaymentAmount,
      paymentInterval: Duration = defaultSubscriptionInterval,
      paymentDuration: Duration = defaultSubscriptionDuration,
      domainId: Option[DomainId] = None,
      description: String = "description",
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val contextId =
      createSubscriptionContext(
        participantClientWithAdminToken,
        userId,
        userParty,
        userParty,
        domainId,
        description,
      )
    val (subscription, payData) =
      createSelfSubscriptionData(contextId, userParty, paymentInterval, paymentDuration, amount)
    val subscriptionRequest = new subsCodegen.SubscriptionRequest(
      subscription,
      payData,
    )
    clue("Create subscription request") {
      participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
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
      participantClientWithAdminToken: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      amount: paymentCodegen.PaymentAmount = defaultSubscriptionAmount,
      paymentInterval: Duration = defaultSubscriptionInterval,
      paymentDuration: Duration = defaultSubscriptionDuration,
      domainId: Option[DomainId] = None,
      description: String = "description",
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val contextId =
      createSubscriptionContext(
        participantClientWithAdminToken,
        userId,
        userParty,
        userParty,
        domainId,
        description,
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
      val result = participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
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
      participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = state.create,
        domainId = domainId,
      )
    }
  }

  protected def grantFeaturedAppRight(wallet: WalletAppClientReference)(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val party = Codec.decode(Codec.Party)(wallet.userStatus().party).value
    actAndCheck(
      "Self-grant a featured app right",
      // We need to retry as the command might failed due to inactive cached CoinRules contract
      // The failed command submission will triggers a cache invalidation
      retryCommandSubmission(wallet.selfGrantFeaturedAppRight()),
    )(
      "Wait for right to be ingested",
      _ => {
        // Featured app rights are looked up either through scan (for 3rd party app transfers), and in the wallet
        // store (to attach to wallet batch operations). We therefore wait for both to be ingested here.
        scan.lookupFeaturedAppRight(party).value
        wallet.userStatus().hasFeaturedAppRight shouldBe true
      },
    )
  }

  /** Directly executes the CoinRules_Mint choice. Note that the receiver must be hosted on the same participant as the SVC. */
  def mintCoin(
      participantClient: CNParticipantClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = scan.getTransferContextWithInstances(now)

    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = aliceWallet.config.ledgerApiUser,
      actAs = Seq(svcParty, receiver),
      readAs = Seq.empty,
      update = tc.coinRules.contractId.exerciseCoinRules_Mint(
        receiver.toLf,
        amount.bigDecimal,
        tc.latestOpenMiningRound.contractId,
      ),
      domainId = domainId,
    )
  }

  /** Directly creates a new coin. Note that the receiver must be hosted on the same participant as the SVC. */
  def createCoin(
      participantClient: CNParticipantClientReference,
      userId: String,
      owner: PartyId,
      amount: BigDecimal = BigDecimal(10),
      round: Long = 0,
      holdingFee: BigDecimal = BigDecimal(0.01),
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val coin = new coinCodegen.Coin(
      svcParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      new feesCodegen.ExpiringAmount(
        amount.bigDecimal,
        new coinApiCodegen.round.Round(round),
        new feesCodegen.RatePerRound(holdingFee.bigDecimal),
      ),
    )
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(svcParty, owner),
      readAs = Seq.empty,
      update = coin.create(),
      domainId = domainId,
    )
  }

  /* Directly archives the given coin. */
  def archiveCoin(
      participantClient: CNParticipantClientReference,
      userId: String,
      coin: Contract[coinCodegen.Coin.ContractId, coinCodegen.Coin],
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(svcParty, PartyId.tryFromProtoPrimitive(coin.payload.owner)),
      readAs = Seq.empty,
      update = coin.contractId.exerciseArchive(
        new com.daml.network.codegen.java.da.internal.template.Archive()
      ),
      domainId = domainId,
    )
  }

  /** Returns the total value of the given reward coupons at current issuing round */
  def getRewardCouponsValue(
      appRewards: Seq[
        Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon]
      ],
      validatorRewards: Seq[
        Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
      ],
      featured: Boolean,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): (BigDecimal, BigDecimal) = {
    val issuanceConfig = scan
      .getOpenAndIssuingMiningRounds()
      ._2
      .lastOption
      .getOrElse(throw new RuntimeException("No issuing round found"))
      .payload

    val issuancePerARC = if (featured) {
      BigDecimal(issuanceConfig.issuancePerFeaturedAppRewardCoupon)
    } else {
      BigDecimal(issuanceConfig.issuancePerUnfeaturedAppRewardCoupon)
    }
    val issuancePerVRC = BigDecimal(issuanceConfig.issuancePerValidatorRewardCoupon)

    val appRewardBalance = appRewards
      .foldLeft(BigDecimal(0))((total, coupon) =>
        total + BigDecimal(coupon.payload.amount) * issuancePerARC
      )
      .setScale(10, BigDecimal.RoundingMode.HALF_EVEN)
    val validatorRewardBalance = validatorRewards
      .foldLeft(BigDecimal(0))((total, coupon) =>
        total + BigDecimal(coupon.payload.amount) * issuancePerVRC
      )
      .setScale(10, BigDecimal.RoundingMode.HALF_EVEN)
    appRewardBalance -> validatorRewardBalance
  }

  protected def retryCommandSubmission[T](f: => T) = {
    eventually() {
      try {
        f
      } catch {
        case ex: CommandExecutionFailedException => {
          logger.debug(s"command failed, triggering retry...")
          fail(ex)
        }
        case ex: Throwable => throw ex // throw anything else
      }
    }
  }

}
