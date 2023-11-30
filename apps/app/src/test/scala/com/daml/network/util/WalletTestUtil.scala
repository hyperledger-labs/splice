package com.daml.network.util

import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.fees as feesCodegen
import com.daml.network.codegen.java.cn.cns as cnsCodegen
import com.daml.network.codegen.java.cn.wallet.{
  install as walletInstallCodegen,
  payment as paymentCodegen,
  subscriptions as subsCodegen,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{ValidatorAppBackendReference, *}
import com.daml.network.http.v0.definitions.GetTransferOfferStatusResponse
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.store.MultiDomainAcsStore.ContractState
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.{Optional, UUID}
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
          coin.contract.payload.owner shouldBe walletParty.toProtoPrimitive
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
      expectedRound: Option[Long],
      expectedUnlockedQtyRange: (BigDecimal, BigDecimal),
      expectedLockedQtyRange: (BigDecimal, BigDecimal),
      expectedHoldingFeeRange: (BigDecimal, BigDecimal),
  ): Unit = clue(s"Checking balance in round $expectedRound") {
    eventually() {
      val balance = wallet.balance()
      expectedRound.foreach(balance.round shouldBe _)
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
    val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    (alice, bob)
  }

  def p2pTransfer(
      senderWallet: WalletAppClientReference,
      receiverWallet: WalletAppClientReference,
      receiver: PartyId,
      amount: BigDecimal,
  ) = {
    val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(1))
    val trackingId = UUID.randomUUID.toString

    val (transferOfferId, _) = actAndCheck(
      "create a transfer offer", {
        senderWallet.createTransferOffer(
          receiver,
          amount,
          "test transfer",
          expiration,
          trackingId,
        )
      },
    )(
      "the transfer offer shows up as created",
      transferOfferId => {
        val status = senderWallet.getTransferOfferStatus(trackingId)
        status.status shouldBe GetTransferOfferStatusResponse.Status.Created

        forExactly(1, receiverWallet.listTransferOffers()) { offer =>
          offer.contractId shouldBe transferOfferId
          offer.payload.trackingId shouldBe trackingId
        }
      },
    )

    actAndCheck(
      "the transfer offer is accepted", {
        receiverWallet.acceptTransferOffer(transferOfferId)
      },
    )(
      "the transfer offer shows up as completed",
      _ => {
        val status = senderWallet.getTransferOfferStatus(trackingId)
        status.status shouldBe GetTransferOfferStatusResponse.Status.Completed
      },
    )
  }

  /** Collects an accepted app payment request without doing anything useful in return. */
  def collectAcceptedAppPaymentRequest(
      participantClient: CNParticipantClientReference,
      userId: String,
      signatories: Seq[PartyId],
      acceptedPayment: Contract[
        paymentCodegen.AcceptedAppPayment.ContractId,
        paymentCodegen.AcceptedAppPayment,
      ],
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc =
      sv1ScanBackend.getTransferContextWithInstances(now, Some(acceptedPayment.payload.round))
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts(tc.coinRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = signatories,
      readAs = Seq(),
      update = acceptedPayment.contractId.exerciseAcceptedAppPayment_Collect(
        appTc
      ),
      domainId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
    )
  }

  /** Rejects an accepted app payment request. */
  def rejectAcceptedAppPaymentRequest(
      participantClient: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      acceptedPayment: paymentCodegen.AcceptedAppPayment.ContractId,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts(tc.coinRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = acceptedPayment.exerciseAcceptedAppPayment_Reject(appTc),
      domainId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
    )
  }

  /** Collects an accepted subscription payment request without doing anything useful in return. */
  def collectAcceptedSubscriptionRequest(
      participantClient: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      sender: PartyId,
      acceptedPayment: Contract[
        subsCodegen.SubscriptionInitialPayment.ContractId,
        subsCodegen.SubscriptionInitialPayment,
      ],
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val now = env.environment.clock.now
    val tc =
      sv1ScanBackend.getTransferContextWithInstances(now, Some(acceptedPayment.payload.round))
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts(tc.coinRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands
      .submitWithResult(
        userId = userId,
        actAs = Seq(userParty, sender),
        readAs = Seq(),
        update = acceptedPayment.contractId.exerciseSubscriptionInitialPayment_Collect(
          appTc
        ),
        domainId = Some(disclosure.assignedDomain),
        disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
      )
      .exerciseResult
  }

  def rejectAcceptedSubscriptionRequest(
      participantClient: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      acceptedPayment: subsCodegen.SubscriptionInitialPayment.ContractId,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts(tc.coinRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = acceptedPayment.exerciseSubscriptionInitialPayment_Reject(
        appTc
      ),
      domainId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
    )
  }

  /** Collects an accepted app payment request without doing anything useful in return. */
  def collectSubscriptionPayment(
      participantClient: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      senderParty: PartyId,
      payment: Contract[subsCodegen.SubscriptionPayment.ContractId, subsCodegen.SubscriptionPayment],
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now, Some(payment.payload.round))
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts(tc.coinRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty, senderParty),
      readAs = Seq(),
      update = payment.contractId.exerciseSubscriptionPayment_Collect(
        appTc
      ),
      domainId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
    )
  }

  def rejectSubscriptionPayment(
      participantClient: CNParticipantClientReference,
      userId: String,
      userParty: PartyId,
      payment: subsCodegen.SubscriptionPayment.ContractId,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts(tc.coinRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = payment.exerciseSubscriptionPayment_Reject(
        appTc
      ),
      domainId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
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

  protected def createDirectoryEntryForDirectoryItself(implicit
      env: CNNodeTestConsoleEnvironment
  ): String = {
    val dirEntryName = "directory.cns"
    val entryUrl = "https://cns-dir-url.com"
    val entryDescription = "Sample CNS Directory Entry Description"
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
      actAs = Seq(svcParty),
      commands = new cnsCodegen.CnsEntry(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        dirEntryName,
        entryUrl,
        entryDescription,
        Instant.now().plus(90, ChronoUnit.DAYS),
      ).create.commands.asScala.toSeq,
      optTimeout = None,
    )
    waitForDirectoryEntry(dirEntryName)
    expectedCns(svcParty, dirEntryName)
  }

  protected def createDirectoryEntry(
      directoryExternalApp: DirectoryExternalAppReference,
      entryName: String,
      wallet: WalletAppClientReference,
      tapAmount: BigDecimal = 5.0,
      entryUrl: String = "https://cns-dir-url.com",
      entryDescription: String = "Sample CNS Directory Entry Description",
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    requestDirectoryEntry(
      directoryExternalApp,
      entryName,
      entryUrl,
      entryDescription,
    )
    wallet.tap(tapAmount)
    eventually() {
      wallet.listSubscriptionRequests() should have length 1
    }
    wallet.acceptSubscriptionRequest(
      wallet.listSubscriptionRequests().head.contractId
    )
    waitForDirectoryEntry(entryName)
  }

  private def waitForDirectoryEntry(name: String)(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    eventuallySucceeds(40.seconds) {
      sv1ScanBackend.lookupEntryByName(name)
    }
  }

  protected def requestDirectoryEntry(
      directoryExternalApp: DirectoryExternalAppReference,
      entryName: String,
      entryUrl: String = "https://cns-dir-url.com",
      entryDescription: String = "Sample CNS Directory Entry Description",
  ) = {
    // TODO(#8300) global domain can be disconnected and reconnected after config of sequencer connections changed
    retryCommandSubmission(
      directoryExternalApp.createDirectoryEntry(entryName, entryUrl, entryDescription)
    )
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
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
  ) = {
    val now = env.environment.clock.now

    val paymentRequest = new paymentCodegen.AppPaymentRequest(
      userParty.toProtoPrimitive,
      receiverAmounts.asJava,
      userParty.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      now.plus(expirationTime).toInstant,
      description,
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

    (requestCid, paymentRequest)
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
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
  ) = {
    val receiverAmounts = Seq(
      receiverAmount(userParty, amount, currency)
    )
    // TODO(#8300) global domain can be disconnected and reconnected after config of sequencer connections changed
    retryCommandSubmission(
      createPaymentRequest(
        participantClientWithAdminToken,
        userId,
        userParty,
        receiverAmounts,
        expirationTime,
        domainId,
        description,
      )
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

  private def createSubscriptionData(
      userParty: PartyId,
      receiverParty: PartyId,
      providerParty: PartyId,
      paymentInterval: Duration,
      paymentDuration: Duration,
      amount: paymentCodegen.PaymentAmount,
      description: String,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val subscription = new subsCodegen.SubscriptionData(
      userParty.toProtoPrimitive,
      receiverParty.toProtoPrimitive,
      providerParty.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      description,
    )
    val payData = new subsCodegen.SubscriptionPayData(
      amount,
      new RelTime(paymentInterval.toMillis * 1000L),
      new RelTime(paymentDuration.toMillis * 1000L),
    )
    (subscription, payData)
  }

  private def createSelfSubscriptionData(
      userParty: PartyId,
      paymentInterval: Duration,
      paymentDuration: Duration,
      amount: paymentCodegen.PaymentAmount,
      description: String,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    createSubscriptionData(
      userParty,
      userParty,
      userParty,
      paymentInterval,
      paymentDuration,
      amount,
      description,
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
      description: String = "description",
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val (subscription, payData) =
      createSubscriptionData(
        userParty,
        receiverParty,
        providerParty,
        paymentInterval,
        paymentDuration,
        amount,
        description,
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
    val (subscription, payData) =
      createSelfSubscriptionData(userParty, paymentInterval, paymentDuration, amount, description)
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
    val (subscriptionData, payData) =
      createSelfSubscriptionData(userParty, paymentInterval, paymentDuration, amount, description)
    val dummyReference = new subsCodegen.SubscriptionRequest.ContractId("00" * 33 + "01")
    val subscriptionId = clue("Create a subscription") {
      val subscription = new subsCodegen.Subscription(
        subscriptionData,
        dummyReference,
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
        dummyReference,
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

  protected def requestCnsEntry(
      participantClientWithAdminToken: CNParticipantClientReference,
      entryName: String,
      userId: String,
      userParty: PartyId,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val entryUrl = "https://cns-dir-url.com"
    val entryDescription = "Sample CNS Entry Description"
    val cnsRules = sv1ScanBackend.getCnsRules()
    val update = cnsRules.contractId.exerciseCnsRules_RequestEntry(
      entryName,
      entryUrl,
      entryDescription,
      userParty.toProtoPrimitive,
    )
    val disclosure = DisclosedContracts(cnsRules)

    clue("request a cns entry from cnsRules contract") {
      val result = participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = update,
        domainId = Some(disclosure.assignedDomain),
        disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
      )
      (
        cnsCodegen.CnsEntryContext.COMPANION.toContractId(result.exerciseResult._1),
        subsCodegen.SubscriptionRequest.COMPANION.toContractId(result.exerciseResult._2),
      )
    }
  }

  protected def grantFeaturedAppRight(wallet: WalletAppClientReference)(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val party = Codec.decode(Codec.Party)(wallet.userStatus().party).value
    actAndCheck(
      "Self-grant a featured app right",
      // We need to retry as the command might fail due to inactive cached CoinRules contract
      // The failed command submission will triggers a cache invalidation
      retryCommandSubmission(wallet.selfGrantFeaturedAppRight()),
    )(
      "Wait for right to be ingested",
      _ => {
        // Featured app rights are looked up either through scan (for 3rd party app transfers), and in the wallet
        // store (to attach to wallet batch operations). We therefore wait for both to be ingested here.
        sv1ScanBackend.lookupFeaturedAppRight(party).value
        wallet.userStatus().hasFeaturedAppRight shouldBe true
      },
    )
  }

  protected def cancelFeaturedAppRight(wallet: WalletAppClientReference)(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val party = Codec.decode(Codec.Party)(wallet.userStatus().party).value
    actAndCheck(
      "Cancel a featured app right",
      retryCommandSubmission(wallet.cancelFeaturedAppRight()),
    )(
      "Wait for right to be ingested",
      _ => {
        sv1ScanBackend.lookupFeaturedAppRight(party).value
        wallet.userStatus().hasFeaturedAppRight shouldBe false
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
    val tc = sv1ScanBackend.getTransferContextWithInstances(now)

    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = aliceWalletClient.config.ledgerApiUser,
      actAs = Seq(svcParty, receiver),
      readAs = Seq.empty,
      update = tc.coinRules.contract.contractId.exerciseCoinRules_Mint(
        receiver.toLf,
        amount.bigDecimal,
        tc.latestOpenMiningRound.contract.contractId,
      ),
      domainId = domainId orElse (tc.coinRules.state match {
        case ContractState.InFlight => None
        case ContractState.Assigned(domain) => Some(domain)
      }),
    )
  }

  /** Directly executes the CoinRules_DevNet_Tap choice. Note that the receiver must be hosted on the same participant as the SVC. */
  def tapCoin(
      participantClient: CNParticipantClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now)

    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = aliceWalletClient.config.ledgerApiUser,
      actAs = Seq(svcParty, receiver),
      readAs = Seq.empty,
      update = tc.coinRules.contract.contractId.exerciseCoinRules_DevNet_Tap(
        receiver.toLf,
        amount.bigDecimal,
        tc.latestOpenMiningRound.contract.contractId,
      ),
      domainId = domainId orElse (tc.coinRules.state match {
        case ContractState.InFlight => None
        case ContractState.Assigned(domain) => Some(domain)
      }),
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
  ): coinCodegen.Coin.ContractId = {
    val coin =
      new coinCodegen.Coin(
        svcParty.toProtoPrimitive,
        owner.toProtoPrimitive,
        new feesCodegen.ExpiringAmount(
          amount.bigDecimal,
          new Round(round),
          new feesCodegen.RatePerRound(holdingFee.bigDecimal),
        ),
        Optional.empty(),
      ).create
    val created = participantClient.ledger_api_extensions.commands
      .submitWithResult(
        userId = userId,
        actAs = Seq(svcParty, owner),
        readAs = Seq.empty,
        update = coin,
        domainId = domainId,
      )
    created.contractId
  }

  /* Directly archives the given coin. */
  def archiveCoin(
      participantClient: CNParticipantClientReference,
      userId: String,
      owner: PartyId,
      coin: coinCodegen.Coin.ContractId,
      domainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(svcParty, owner),
      readAs = Seq.empty,
      update = coin.exerciseArchive(
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
    val issuanceConfig = sv1ScanBackend
      .getOpenAndIssuingMiningRounds()
      ._2
      .lastOption
      .getOrElse(throw new RuntimeException("No issuing round found"))
      .contract
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
        case ex: CommandFailure => {
          logger.debug(s"command failed, triggering retry...")
          fail(ex)
        }
        case ex: Throwable => throw ex // throw anything else
      }
    }
  }
}
