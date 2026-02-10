package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.expiry.TimeLock
import org.lfdecentralizedtrust.splice.codegen.java.splice.fees as feesCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans as ansCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  install as walletInstallCodegen,
  payment as paymentCodegen,
  subscriptions as subsCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  TransferOutput,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.console.{ValidatorAppBackendReference, *}
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.util.WalletTestUtil.{DynamicUserRefs, StaticUserRefs}
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.LockedAmulet
import org.lfdecentralizedtrust.splice.environment.PackageVersionSupport
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient.CreateTransferPreapprovalResponse
import org.scalatest.Assertion

import java.time.Duration
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.math.BigDecimal.RoundingMode
import scala.util.control.NonFatal

trait WalletTestUtil extends TestCommon with AnsTestUtil {
  this: CommonAppInstanceReferences =>

  val exactly = (x: BigDecimal) => (x, x)

  val defaultWalletAmuletPrice = SpliceUtil.damlDecimal(0.005)
  def walletAmuletPrice = defaultWalletAmuletPrice

  def walletUsdToAmulet(usd: BigDecimal, amuletPrice: BigDecimal = walletAmuletPrice): BigDecimal =
    (usd / amuletPrice).setScale(10, RoundingMode.HALF_UP)
  def walletAmuletToUsd(cc: BigDecimal, amuletPrice: BigDecimal = walletAmuletPrice): BigDecimal =
    (cc * amuletPrice).setScale(10, RoundingMode.HALF_UP)

  lazy val defaultHoldingFeeAmulet = walletUsdToAmulet(SpliceUtil.defaultHoldingFee.rate)

  /** @param expectedAmountRanges : lower and upper bounds for amulets sorted by their initial amount in ascending order. */
  def checkWallet(
      walletParty: PartyId,
      wallet: WalletAppClientReference,
      expectedAmountRanges: Seq[(BigDecimal, BigDecimal)],
      holdingFee: BigDecimal = defaultHoldingFeeAmulet.bigDecimal,
  ): Unit = clue(s"checking wallet with $expectedAmountRanges") {
    val expectedRatePerRound = new feesCodegen.RatePerRound(
      holdingFee.bigDecimal setScale 10
    )
    eventually(10.seconds, 500.millis) {
      val amulets =
        wallet.list().amulets.sortBy(amulet => amulet.contract.payload.amount.initialAmount)
      amulets should have size (expectedAmountRanges.size.toLong)
      amulets
        .zip(expectedAmountRanges)
        .foreach { case (amulet, amountBounds) =>
          amulet.contract.payload.owner shouldBe walletParty.toProtoPrimitive
          val amuletAmount =
            amulet.contract.payload.amount

          assertInRange(amuletAmount.initialAmount, amountBounds)
          amuletAmount.ratePerRound shouldBe expectedRatePerRound
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
    ledgerApiEx.acs.filterJava(amuletCodegen.ValidatorRight.COMPANION)(
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
      endUserParty: PartyId,
  ): org.scalatest.Assertion = {
    // Wallet must report that user is not onboarded
    val status =
      try {
        loggerFactory.suppressWarningsAndErrors { // fine, we're failing below.
          walletAppClient.userStatus()
        }
      } catch {
        // User-status can fail due to the offboarded user's wallet being in the process of shutdown at the time of request.
        // Retrying would fix it.
        case NonFatal(ex) =>
          fail(
            s"User status failed. This should be a transient error, until the user's wallet is removed from the UserWalletManager.",
            ex,
          )
      }
    status.userOnboarded shouldBe false
    status.userWalletInstalled shouldBe false

    // Assert that the user is no longer present in the participant's user list
    val userList = validatorAppBackend.participantClientWithAdminToken.ledger_api.users.list()
    userList.users.map(_.id) should not contain walletAppClient.config.ledgerApiUser

    // Validator user must not have any rights for the end user party
    val ledgerApi = validatorAppBackend.participantClientWithAdminToken.ledger_api
    val validatorRights = ledgerApi.users.rights.list(validatorAppBackend.config.ledgerApiUser)
    validatorRights.readAs should not contain endUserParty
    validatorRights.actAs should not contain endUserParty

    // All validator right and wallet install contracts must be gone
    val ledgerApiEx = validatorAppBackend.participantClientWithAdminToken.ledger_api_extensions
    ledgerApiEx.acs.filterJava(amuletCodegen.ValidatorRight.COMPANION)(
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
      env: SpliceTestConsoleEnvironment
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
      timeUntilSuccess: FiniteDuration = 20.seconds,
  ) = {
    val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(1))
    val trackingId = UUID.randomUUID.toString

    val (transferOfferId, _) = actAndCheck(timeUntilSuccess)(
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
        inside(senderWallet.getTransferOfferStatus(trackingId)) {
          case d0.GetTransferOfferStatusResponse.members.TransferOfferCreatedResponse(response) =>
            response.status shouldBe TxLogEntry.Http.TransferOfferStatus.Created
        }
        forExactly(1, receiverWallet.listTransferOffers()) { offer =>
          offer.contractId shouldBe transferOfferId
          offer.payload.trackingId shouldBe trackingId
        }
      },
    )

    actAndCheck(timeUntilSuccess)(
      "the transfer offer is accepted", {
        receiverWallet.acceptTransferOffer(transferOfferId)
      },
    )(
      "the transfer offer shows up as completed",
      _ => {
        inside(senderWallet.getTransferOfferStatus(trackingId)) {
          case d0.GetTransferOfferStatusResponse.members.TransferOfferCompletedResponse(response) =>
            response.status shouldBe TxLogEntry.Http.TransferOfferStatus.Completed
        }
        inside(receiverWallet.getTransferOfferStatus(trackingId)) {
          case d0.GetTransferOfferStatusResponse.members.TransferOfferCompletedResponse(response) =>
            response.status shouldBe TxLogEntry.Http.TransferOfferStatus.Completed
        }
      },
    )
  }

  /** Collects an accepted app payment request without doing anything useful in return. */
  def collectAcceptedAppPaymentRequest(
      participantClient: ParticipantClientReference,
      userId: String,
      signatories: Seq[PartyId],
      acceptedPayment: ContractWithState[
        paymentCodegen.AcceptedAppPayment.ContractId,
        paymentCodegen.AcceptedAppPayment,
      ],
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc =
      sv1ScanBackend.getTransferContextWithInstances(now, Some(acceptedPayment.payload.round))
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts.forTesting(tc.amuletRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = signatories,
      readAs = Seq(),
      update = acceptedPayment.contractId.exerciseAcceptedAppPayment_Collect(
        appTc
      ),
      synchronizerId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
    )
  }

  /** Rejects an accepted app payment request. */
  def rejectAcceptedAppPaymentRequest(
      participantClient: ParticipantClientReference,
      userId: String,
      userParty: PartyId,
      acceptedPayment: paymentCodegen.AcceptedAppPayment.ContractId,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts.forTesting(tc.amuletRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = acceptedPayment.exerciseAcceptedAppPayment_Reject(appTc),
      synchronizerId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
    )
  }

  /** Collects an accepted subscription payment request without doing anything useful in return. */
  def collectAcceptedSubscriptionRequest(
      participantClient: ParticipantClientReference,
      userId: String,
      userParty: PartyId,
      sender: PartyId,
      acceptedPayment: Contract[
        subsCodegen.SubscriptionInitialPayment.ContractId,
        subsCodegen.SubscriptionInitialPayment,
      ],
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val now = env.environment.clock.now
    val tc =
      sv1ScanBackend.getTransferContextWithInstances(now, Some(acceptedPayment.payload.round))
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts.forTesting(tc.amuletRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands
      .submitWithResult(
        userId = userId,
        actAs = Seq(userParty, sender),
        readAs = Seq(),
        update = acceptedPayment.contractId.exerciseSubscriptionInitialPayment_Collect(
          appTc
        ),
        synchronizerId = Some(disclosure.assignedDomain),
        disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
      )
      .exerciseResult
  }

  def rejectAcceptedSubscriptionRequest(
      participantClient: ParticipantClientReference,
      userId: String,
      userParty: PartyId,
      acceptedPayment: subsCodegen.SubscriptionInitialPayment.ContractId,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts.forTesting(tc.amuletRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = acceptedPayment.exerciseSubscriptionInitialPayment_Reject(
        appTc
      ),
      synchronizerId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
    )
  }

  /** Collects an accepted app payment request without doing anything useful in return. */
  def collectSubscriptionPayment(
      participantClient: ParticipantClientReference,
      userId: String,
      userParty: PartyId,
      senderParty: PartyId,
      payment: Contract[subsCodegen.SubscriptionPayment.ContractId, subsCodegen.SubscriptionPayment],
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now, Some(payment.payload.round))
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts.forTesting(tc.amuletRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty, senderParty),
      readAs = Seq(),
      update = payment.contractId.exerciseSubscriptionPayment_Collect(
        appTc
      ),
      synchronizerId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
    )
  }

  def rejectSubscriptionPayment(
      participantClient: ParticipantClientReference,
      userId: String,
      userParty: PartyId,
      payment: subsCodegen.SubscriptionPayment.ContractId,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now)
    val appTc = tc.toUnfeaturedAppTransferContext()
    val disclosure = DisclosedContracts.forTesting(tc.amuletRules, tc.latestOpenMiningRound)
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(userParty),
      readAs = Seq(),
      update = payment.exerciseSubscriptionPayment_Reject(
        appTc
      ),
      synchronizerId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
    )
  }

  /** Expires a subscription that has not been paid in time. */
  def expireUnpaidSubscription(
      participantClient: ParticipantClientReference,
      userId: String,
      actor: PartyId,
      subscriptionIdleState: subsCodegen.SubscriptionIdleState.ContractId,
      synchronizerId: Option[SynchronizerId] = None,
  ): Unit = {
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(actor),
      readAs = Seq(),
      update = subscriptionIdleState.exerciseSubscriptionIdleState_ExpireSubscription(
        actor.toProtoPrimitive
      ),
      synchronizerId = synchronizerId,
      disclosedContracts = Seq.empty,
    )
  }
  protected def expectedDsoAns(implicit env: SpliceTestConsoleEnvironment): String = {
    expectedAns(dsoParty, DsoAnsResolver.dsoAnsName(ansAcronym))
  }

  protected def createAnsEntry(
      ansExternalApp: AnsExternalAppReference,
      entryName: String,
      wallet: WalletAppClientReference,
      tapAmount: BigDecimal = 5.0,
      entryUrl: String = testEntryUrl,
      entryDescription: String = testEntryDescription,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    requestAnsEntry(
      ansExternalApp,
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
    waitForAnsEntry(entryName)
  }

  private def waitForAnsEntry(name: String)(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    eventuallySucceeds(40.seconds) {
      sv1ScanBackend.lookupEntryByName(name)
    }
  }

  protected def requestAnsEntry(
      ansExternalApp: AnsExternalAppReference,
      entryName: String,
      entryUrl: String = testEntryUrl,
      entryDescription: String = testEntryDescription,
  ) = {
    // TODO(#979) global domain can be disconnected and reconnected after config of sequencer connections changed
    retryCommandSubmission(
      ansExternalApp.createAnsEntry(entryName, entryUrl, entryDescription)
    )
  }

  def paymentAmount(
      amount: BigDecimal,
      unit: paymentCodegen.Unit,
  ) =
    new paymentCodegen.PaymentAmount(
      amount.bigDecimal,
      unit,
    )

  def receiverAmount(
      receiverParty: PartyId,
      amount: BigDecimal,
      unit: paymentCodegen.Unit,
  ) =
    new paymentCodegen.ReceiverAmount(
      receiverParty.toProtoPrimitive,
      new paymentCodegen.PaymentAmount(
        amount.bigDecimal,
        unit,
      ),
    )

  def createPaymentRequest(
      participantClientWithAdminToken: ParticipantClientReference,
      userId: String,
      userParty: PartyId,
      receiverAmounts: Seq[paymentCodegen.ReceiverAmount],
      expirationTime: Duration = Duration.ofMinutes(5),
      synchronizerId: Option[SynchronizerId] = None,
      description: String = "description",
  )(implicit env: SpliceTestConsoleEnvironment): (
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
  ) = {
    val now = env.environment.clock.now

    val paymentRequest = new paymentCodegen.AppPaymentRequest(
      userParty.toProtoPrimitive,
      receiverAmounts.asJava,
      userParty.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
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
        synchronizerId = synchronizerId,
      )
      paymentCodegen.AppPaymentRequest.COMPANION.toContractId(result.contractId)
    }

    (requestCid, paymentRequest)
  }

  def createSelfPaymentRequest(
      participantClientWithAdminToken: ParticipantClientReference,
      userId: String,
      userParty: PartyId,
      amount: BigDecimal = defaultPaymentAmount.amount,
      unit: paymentCodegen.Unit = defaultPaymentAmount.unit,
      expirationTime: Duration = Duration.ofMinutes(5),
      synchronizerId: Option[SynchronizerId] = None,
      description: String = "description",
  )(implicit env: SpliceTestConsoleEnvironment): (
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
  ) = {
    val receiverAmounts = Seq(
      receiverAmount(userParty, amount, unit)
    )
    // TODO(#979) global domain can be disconnected and reconnected after config of sequencer connections changed
    retryCommandSubmission(
      createPaymentRequest(
        participantClientWithAdminToken,
        userId,
        userParty,
        receiverAmounts,
        expirationTime,
        synchronizerId,
        description,
      )
    )
  }

  private val defaultSubscriptionAmount = new paymentCodegen.PaymentAmount(
    BigDecimal(10).bigDecimal.setScale(10),
    paymentCodegen.Unit.AMULETUNIT,
  )
  private val defaultSubscriptionInterval = Duration.ofMinutes(10)
  private val defaultSubscriptionDuration = Duration.ofMinutes(60)
  private val defaultPaymentAmount = new paymentCodegen.PaymentAmount(
    BigDecimal(10).bigDecimal.setScale(10),
    paymentCodegen.Unit.AMULETUNIT,
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
      env: SpliceTestConsoleEnvironment
  ) = {
    val subscription = new subsCodegen.SubscriptionData(
      userParty.toProtoPrimitive,
      receiverParty.toProtoPrimitive,
      providerParty.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
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
      env: SpliceTestConsoleEnvironment
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
      participantClientWithAdminToken: ParticipantClientReference,
      userId: String,
      userParty: PartyId,
      receiverParty: PartyId,
      providerParty: PartyId,
      amount: paymentCodegen.PaymentAmount = defaultPaymentAmount,
      paymentInterval: Duration = defaultSubscriptionInterval,
      paymentDuration: Duration = defaultSubscriptionDuration,
      description: String = "description",
      synchronizerId: Option[SynchronizerId] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
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
        synchronizerId = synchronizerId,
      )
    }
    subscriptionRequest
  }

  protected def createSelfSubscriptionRequest(
      participantClientWithAdminToken: ParticipantClientReference,
      userId: String,
      userParty: PartyId,
      amount: paymentCodegen.PaymentAmount = defaultPaymentAmount,
      paymentInterval: Duration = defaultSubscriptionInterval,
      paymentDuration: Duration = defaultSubscriptionDuration,
      synchronizerId: Option[SynchronizerId] = None,
      description: String = "description",
  )(implicit
      env: SpliceTestConsoleEnvironment
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
        synchronizerId = synchronizerId,
      )
    }
    subscriptionRequest
  }

  protected def createSelfSubscription(
      participantClientWithAdminToken: ParticipantClientReference,
      userId: String,
      userParty: PartyId,
      amount: paymentCodegen.PaymentAmount = defaultSubscriptionAmount,
      paymentInterval: Duration = defaultSubscriptionInterval,
      paymentDuration: Duration = defaultSubscriptionDuration,
      synchronizerId: Option[SynchronizerId] = None,
      description: String = "description",
  )(implicit
      env: SpliceTestConsoleEnvironment
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
        synchronizerId = synchronizerId,
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
        synchronizerId = synchronizerId,
      )
    }
  }

  protected def requestAnsEntry(
      participantClientWithAdminToken: ParticipantClientReference,
      entryName: String,
      userId: String,
      userParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    val ansRules = sv1ScanBackend.getAnsRules()
    val update = ansRules.contractId.exerciseAnsRules_RequestEntry(
      entryName,
      testEntryUrl,
      testEntryDescription,
      userParty.toProtoPrimitive,
    )
    val disclosure = DisclosedContracts.forTesting(ansRules)

    clue("request a ans entry from ansRules contract") {
      val result = participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = update,
        synchronizerId = Some(disclosure.assignedDomain),
        disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
      )
      (
        ansCodegen.AnsEntryContext.COMPANION.toContractId(result.exerciseResult.entryCid),
        subsCodegen.SubscriptionRequest.COMPANION.toContractId(result.exerciseResult.requestCid),
      )
    }
  }

  protected def grantFeaturedAppRight(wallet: WalletAppClientReference)(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val party = Codec.decode(Codec.Party)(wallet.userStatus().party).value
    actAndCheck(
      "Self-grant a featured app right",
      // We need to retry as the command might fail due to inactive cached AmuletRules contract
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
      env: SpliceTestConsoleEnvironment
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

  /** Directly executes the AmuletRules_Mint choice. Note that the receiver must be hosted on the same participant as the DSO. */
  def mintAmulet(
      participantClient: ParticipantClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      synchronizerId: Option[SynchronizerId] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now)

    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = aliceWalletClient.config.ledgerApiUser,
      actAs = Seq(dsoParty, receiver),
      readAs = Seq.empty,
      update = tc.amuletRules.contract.contractId.exerciseAmuletRules_Mint(
        receiver.toLf,
        amount.bigDecimal,
        tc.latestOpenMiningRound.contract.contractId,
      ),
      synchronizerId = synchronizerId orElse (tc.amuletRules.state match {
        case ContractState.InFlight => None
        case ContractState.Assigned(domain) => Some(domain)
      }),
    )
  }

  /** Directly executes the AmuletRules_DevNet_Tap choice. Note that the receiver must be hosted on the same participant as the DSO. */
  def tapAmulet(
      participantClient: ParticipantClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      synchronizerId: Option[SynchronizerId] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val now = env.environment.clock.now
    val tc = sv1ScanBackend.getTransferContextWithInstances(now)

    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = aliceWalletClient.config.ledgerApiUser,
      actAs = Seq(receiver),
      readAs = Seq(dsoParty),
      update = tc.amuletRules.contract.contractId.exerciseAmuletRules_DevNet_Tap(
        receiver.toLf,
        amount.bigDecimal,
        tc.latestOpenMiningRound.contract.contractId,
      ),
      synchronizerId = synchronizerId orElse (tc.amuletRules.state match {
        case ContractState.InFlight => None
        case ContractState.Assigned(domain) => Some(domain)
      }),
    )
  }

  /** Directly creates a new amulet. Note that the receiver must be hosted on the same participant as the DSO. */
  def createAmulet(
      participantClient: ParticipantClientReference,
      userId: String,
      owner: PartyId,
      amount: BigDecimal = BigDecimal(10),
      round: Long = 0,
      holdingFee: BigDecimal = BigDecimal(0.01),
      synchronizerId: Option[SynchronizerId] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): amuletCodegen.Amulet.ContractId = {
    val amulet =
      new amuletCodegen.Amulet(
        dsoParty.toProtoPrimitive,
        owner.toProtoPrimitive,
        new feesCodegen.ExpiringAmount(
          amount.bigDecimal,
          new Round(round),
          new feesCodegen.RatePerRound(holdingFee.bigDecimal),
        ),
      ).create
    val created = participantClient.ledger_api_extensions.commands
      .submitWithResult(
        userId = userId,
        actAs = Seq(dsoParty, owner),
        readAs = Seq.empty,
        update = amulet,
        synchronizerId = synchronizerId,
      )
    created.contractId
  }

  /** Directly creates a new LockedAmulet amulet. Note that the receiver and lock hodlers must be hosted on the same participant as the DSO. */
  def createLockedAmulet(
      participantClient: ParticipantClientReference,
      userId: String,
      owner: PartyId,
      lockHolders: Seq[PartyId],
      amount: BigDecimal = BigDecimal(10),
      expiredDuration: Duration = Duration.ofMinutes(5),
      round: Long = 0,
      holdingFee: BigDecimal = BigDecimal(0.01),
      synchronizerId: Option[SynchronizerId] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): amuletCodegen.LockedAmulet.ContractId = {
    val amulet =
      new amuletCodegen.Amulet(
        dsoParty.toProtoPrimitive,
        owner.toProtoPrimitive,
        new feesCodegen.ExpiringAmount(
          amount.bigDecimal,
          new Round(round),
          new feesCodegen.RatePerRound(holdingFee.bigDecimal),
        ),
      )
    val expiredAt = env.environment.clock.now.add(expiredDuration)
    val expiration = Codec.decode(Codec.Timestamp)(expiredAt.underlying.micros).value
    val lockedAmulet = new LockedAmulet(
      amulet,
      new TimeLock(
        lockHolders.map(_.toProtoPrimitive).asJava,
        expiration.toInstant,
        None.toJava,
      ),
    )
    val created = participantClient.ledger_api_extensions.commands
      .submitWithResult(
        userId = userId,
        actAs = Seq(dsoParty, owner),
        readAs = Seq.empty,
        update = lockedAmulet.create(),
        synchronizerId = synchronizerId,
      )
    created.contractId
  }

  /* Directly archives the given amulet. */
  def archiveAmulet(
      participantClient: ParticipantClientReference,
      userId: String,
      owner: PartyId,
      amulet: amuletCodegen.Amulet.ContractId,
      synchronizerId: Option[SynchronizerId] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    participantClient.ledger_api_extensions.commands.submitWithResult(
      userId = userId,
      actAs = Seq(dsoParty, owner),
      readAs = Seq.empty,
      update = amulet.exerciseArchive(
        new org.lfdecentralizedtrust.splice.codegen.java.da.internal.template.Archive()
      ),
      synchronizerId = synchronizerId,
    )
  }

  /** Returns the total value of the given reward coupons at current issuing round */
  def getRewardCouponsValue(
      appRewards: Seq[
        Contract[amuletCodegen.AppRewardCoupon.ContractId, amuletCodegen.AppRewardCoupon]
      ],
      validatorRewards: Seq[
        Contract[
          amuletCodegen.ValidatorRewardCoupon.ContractId,
          amuletCodegen.ValidatorRewardCoupon,
        ]
      ],
      featured: Boolean,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): (BigDecimal, BigDecimal) =
    // use eventually so that round advancement has some time to propagate to scan
    eventually() {
      val issuingRounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._2
      def getIssuanceConfig(roundNumber: Long) =
        issuingRounds
          .collectFirst {
            case round if round.contract.payload.round.number == roundNumber =>
              round.contract.payload
          }
          .getOrElse(
            fail(s"Could not find issuing round for round number $roundNumber")
          )

      val appRewardBalance = appRewards
        .foldLeft(BigDecimal(0))((total, coupon) => {
          val issuanceConfig = getIssuanceConfig(coupon.payload.round.number)
          val issuancePerARC = if (featured) {
            BigDecimal(issuanceConfig.issuancePerFeaturedAppRewardCoupon)
          } else {
            BigDecimal(issuanceConfig.issuancePerUnfeaturedAppRewardCoupon)
          }
          total + BigDecimal(coupon.payload.amount) * issuancePerARC
        })
        .setScale(10, BigDecimal.RoundingMode.HALF_EVEN)

      val validatorRewardBalance = validatorRewards
        .foldLeft(BigDecimal(0))((total, coupon) => {
          val issuanceConfig = getIssuanceConfig(coupon.payload.round.number)
          val issuancePerVRC = BigDecimal(issuanceConfig.issuancePerValidatorRewardCoupon)
          total + BigDecimal(coupon.payload.amount) * issuancePerVRC
        })
        .setScale(10, BigDecimal.RoundingMode.HALF_EVEN)

      appRewardBalance -> validatorRewardBalance
    }

  /** Directly creates a new unclaimed reward. */
  def createUnclaimedReward(
      participantClient: ParticipantClientReference,
      userId: String,
      amount: BigDecimal = BigDecimal(10),
      synchronizerId: Option[SynchronizerId] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): amuletCodegen.UnclaimedReward.ContractId = {
    val unclaimedReward =
      new amuletCodegen.UnclaimedReward(
        dsoParty.toProtoPrimitive,
        amount.bigDecimal,
      ).create
    val created = participantClient.ledger_api_extensions.commands
      .submitWithResult(
        userId = userId,
        actAs = Seq(dsoParty),
        readAs = Seq.empty,
        update = unclaimedReward,
        synchronizerId = synchronizerId,
      )
    created.contractId
  }

  /** Directly creates a new unclaimed development fund coupon. */
  def createUnclaimedDevelopmentFundCoupon(
      participantClient: ParticipantClientReference,
      userId: String,
      amount: BigDecimal = BigDecimal(10),
      synchronizerId: Option[SynchronizerId] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): amuletCodegen.UnclaimedDevelopmentFundCoupon.ContractId = {
    val amulet =
      new amuletCodegen.UnclaimedDevelopmentFundCoupon(
        dsoParty.toProtoPrimitive,
        amount.bigDecimal,
      ).create
    val created = participantClient.ledger_api_extensions.commands
      .submitWithResult(
        userId = userId,
        actAs = Seq(dsoParty),
        readAs = Seq.empty,
        update = amulet,
        synchronizerId = synchronizerId,
      )
    created.contractId
  }

  /** Directly creates a new development fund coupon. */
  def createDevelopmentFundCoupon(
      participantClient: ParticipantClientReference,
      userId: String,
      beneficiary: PartyId,
      fundManager: PartyId,
      amount: BigDecimal = BigDecimal(10),
      expiresAt: CantonTimestamp,
      reason: String,
      synchronizerId: Option[SynchronizerId] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): amuletCodegen.DevelopmentFundCoupon.ContractId = {
    val coupon =
      new amuletCodegen.DevelopmentFundCoupon(
        dsoParty.toProtoPrimitive,
        beneficiary.toProtoPrimitive,
        fundManager.toProtoPrimitive,
        amount.bigDecimal,
        expiresAt.toInstant,
        reason,
      ).create
    val created = participantClient.ledger_api_extensions.commands
      .submitWithResult(
        userId = userId,
        actAs = Seq(dsoParty),
        readAs = Seq.empty,
        update = coupon,
        synchronizerId = synchronizerId,
      )
    created.contractId
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

  protected def setupUser(refs: StaticUserRefs): DynamicUserRefs = {
    val userParty = onboardWalletUser(refs.wallet, refs.validator)
    DynamicUserRefs(userParty, refs)
  }

  protected def requestEntry(
      refs: DynamicUserRefs,
      entryName: String,
      entryUrl: String = testEntryUrl,
      entryDescription: String = testEntryDescription,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    val ansRules = sv1ScanBackend.getAnsRules()

    val cmd = ansRules.contractId.exerciseAnsRules_RequestEntry(
      entryName,
      entryUrl,
      entryDescription,
      refs.userParty.toProtoPrimitive,
    )
    refs.validator.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitWithResult(
        userId = refs.validator.config.ledgerApiUser,
        actAs = Seq(refs.userParty),
        readAs = Seq.empty,
        update = cmd,
        disclosedContracts = DisclosedContracts.forTesting(ansRules).toLedgerApiDisclosedContracts,
      )
      .exerciseResult
      .requestCid
  }

  protected def requestAndPayForEntry(
      refs: DynamicUserRefs,
      entryName: String,
      entryUrl: String = testEntryUrl,
      entryDescription: String = testEntryDescription,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    // for paying the ans entry initial payment.
    refs.wallet.tap(5.0)

    val subscriptionRequest = requestEntry(refs, entryName, entryUrl, entryDescription)

    actAndCheck(
      s"Wait for subscription request to be ingested into store and accept it.",
      eventually() {
        inside(refs.wallet.listSubscriptionRequests()) { case Seq(storeRequest) =>
          storeRequest.contractId shouldBe subscriptionRequest
          refs.wallet.acceptSubscriptionRequest(storeRequest.contractId)
        }
      },
    )(
      s" Wait for the payment to be accepted or rejected.",
      _ => refs.wallet.listSubscriptionInitialPayments() shouldBe empty,
    )
  }

  def ensureValidatorLivenessActivityRecordReceivedForCurrentRound(
      scanBackend: ScanAppBackendReference,
      walletClient: WalletAppClientReference,
  ): Assertion = {
    val currentRound =
      scanBackend
        .getOpenAndIssuingMiningRounds()
        ._1
        .head
        .contract
        .payload
        .round
        .number
    (walletClient
      .listValidatorLivenessActivityRecords()
      .map(_.payload.round.number) should contain(currentRound))
      .withClue(
        s"Wallet: ${walletClient.name} did not receive a ValidatorLivenessActivityRecord for round $currentRound."
      )
  }

  def ensureNoValidatorLivenessActivityRecordExistsForRound(
      round: Long,
      walletClient: WalletAppClientReference,
  ): Assertion = {
    inside(
      walletClient
        .listValidatorLivenessActivityRecords()
    ) { case coupons =>
      coupons.map(_.payload.round.number) should not(contain(round))
    }
  }

  def transferOutputAmulet(
      receiver: PartyId,
      receiverFeeRatio: BigDecimal,
      amount: BigDecimal,
  ): splice.amuletrules.TransferOutput = {
    new TransferOutput(
      receiver.toProtoPrimitive,
      receiverFeeRatio.bigDecimal,
      amount.bigDecimal,
      None.toJava,
    )
  }

  def transferOutputLockedAmulet(
      receiver: PartyId,
      lockHolders: Seq[PartyId],
      receiverFeeRatio: BigDecimal,
      amount: BigDecimal,
      expiredDuration: Duration,
  )(implicit env: SpliceTestConsoleEnvironment): splice.amuletrules.TransferOutput = {
    val expiredAt = env.environment.clock.now.add(expiredDuration)
    val expiration = Codec.decode(Codec.Timestamp)(expiredAt.underlying.micros).value

    new TransferOutput(
      receiver.toProtoPrimitive,
      receiverFeeRatio.bigDecimal,
      amount.bigDecimal,
      Some(
        new TimeLock(
          lockHolders.map(_.toProtoPrimitive).asJava,
          expiration.toInstant,
          None.toJava,
        )
      ).toJava,
    )
  }

  def lockAmulets(
      userValidator: ValidatorAppBackendReference,
      userParty: PartyId,
      validatorParty: PartyId,
      amulets: Seq[HttpWalletAppClient.AmuletPosition],
      amount: BigDecimal,
      scan: ScanAppBackendReference,
      expiredDuration: Duration,
      ledgerTime: CantonTimestamp,
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    clue(s"Locking $amount amulets for $userParty") {
      val amulet = amulets
        .find(_.effectiveAmount >= amount)
        .valueOrFail(
          s"No amulet found with enough effective amount. Amulet effectiveAmounts: ${amulets.map(_.effectiveAmount)}"
        )
      val amuletRules = scan.getAmuletRules()
      val transferContext = scan.getUnfeaturedAppTransferContext(ledgerTime)
      val openRound = scan.getLatestOpenMiningRound(ledgerTime)

      val supportsExpectedDsoParty =
        validatorSupportsExpectedDsoParty(amuletRules, userValidator, env.environment.clock.now)(
          env.executionContext
        )
      userValidator.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
        Seq(userParty, validatorParty),
        commands = transferContext.amuletRules
          .exerciseAmuletRules_Transfer(
            new splice.amuletrules.Transfer(
              userParty.toProtoPrimitive,
              userParty.toProtoPrimitive,
              Seq[splice.amuletrules.TransferInput](
                new splice.amuletrules.transferinput.InputAmulet(
                  amulet.contract.contractId
                )
              ).asJava,
              Seq[splice.amuletrules.TransferOutput](
                transferOutputLockedAmulet(
                  userParty,
                  Seq(userParty),
                  BigDecimal(0.0),
                  amount,
                  expiredDuration,
                )
              ).asJava,
              java.util.Optional.empty(),
            ),
            new splice.amuletrules.TransferContext(
              transferContext.openMiningRound,
              Map.empty[Round, IssuingMiningRound.ContractId].asJava,
              Map.empty[String, splice.amulet.ValidatorRight.ContractId].asJava,
              // note: we don't provide a featured app right as sender == provider
              None.toJava,
            ),
            Option.when(supportsExpectedDsoParty)(amuletRules.payload.dso).toJava,
          )
          .commands
          .asScala
          .toSeq,
        disclosedContracts =
          DisclosedContracts.forTesting(amuletRules, openRound).toLedgerApiDisclosedContracts,
      )
    }

  /** Directly exercises the AmuletRules_Transfer choice.
    * Note that all parties participating in the transfer need to be hosted on the same participant
    */
  def rawTransfer(
      userValidator: ValidatorAppBackendReference,
      userId: String,
      userParty: PartyId,
      validatorParty: PartyId,
      amulet: HttpWalletAppClient.AmuletPosition,
      outputs: Seq[splice.amuletrules.TransferOutput],
      now: CantonTimestamp,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    val amuletRules = sv1ScanBackend.getAmuletRules()
    val transferContext = sv1ScanBackend.getUnfeaturedAppTransferContext(now)
    val openRound = sv1ScanBackend.getLatestOpenMiningRound(now)
    val supportsExpectedDsoParty =
      validatorSupportsExpectedDsoParty(amuletRules, userValidator, now)(
        env.executionContext
      )
    val authorizers =
      Seq(userParty, validatorParty) ++ outputs.map(o => PartyId.tryFromProtoPrimitive(o.receiver))

    val disclosure = DisclosedContracts.forTesting(amuletRules, openRound)

    userValidator.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
      userId = userId,
      actAs = authorizers.distinct,
      readAs = Seq.empty,
      commands = transferContext.amuletRules
        .exerciseAmuletRules_Transfer(
          new splice.amuletrules.Transfer(
            userParty.toProtoPrimitive,
            userParty.toProtoPrimitive,
            Seq[splice.amuletrules.TransferInput](
              new splice.amuletrules.transferinput.InputAmulet(
                amulet.contract.contractId
              )
            ).asJava,
            outputs.asJava,
            java.util.Optional.empty(),
          ),
          new splice.amuletrules.TransferContext(
            transferContext.openMiningRound,
            Map.empty[Round, IssuingMiningRound.ContractId].asJava,
            Map.empty[String, splice.amulet.ValidatorRight.ContractId].asJava,
            // note: we don't provide a featured app right as sender == provider
            None.toJava,
          ),
          Option.when(supportsExpectedDsoParty)(amuletRules.payload.dso).toJava,
        )
        .commands
        .asScala
        .toSeq,
      synchronizerId = Some(disclosure.assignedDomain),
      disclosedContracts = disclosure.toLedgerApiDisclosedContracts,
    )
  }

  def validatorSupportsExpectedDsoParty(
      amuletRules: ContractWithState[AmuletRules.ContractId, AmuletRules],
      userValidator: ValidatorAppBackendReference,
      now: CantonTimestamp,
  )(implicit ec: ExecutionContext): Boolean = {
    val synchronizerId = amuletRules.state.fold(
      synchronizerId => synchronizerId,
      fail("Expected AmuletRules to be assigned to a synchronizer."),
    )
    val packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
      synchronizerId,
      userValidator.validatorAutomation.connection(SpliceLedgerConnectionPriority.Low),
      loggerFactory,
    )
    val partiesOfInterest = Seq(
      userValidator.getValidatorPartyId(),
      PartyId.tryFromProtoPrimitive(amuletRules.contract.payload.dso),
    )
    packageVersionSupport
      .supportsExpectedDsoParty(partiesOfInterest, now)
      .futureValue
      .supported
  }

  /** @param partyWalletClient the wallet in which to create the transfer preapproval
    * @param checkValidator a validator to use to check that the preapproval was created and ingested by enough Scans
    */
  def createTransferPreapprovalEnsuringItExists(
      partyWalletClient: WalletAppClientReference,
      checkValidator: ValidatorAppBackendReference,
  ): TransferPreapproval.ContractId = {
    // creating the transfer preapproval can fail because there are no funds (which this won't recover),
    // but also by the validator being slow to approve the preapproval, which we can recover here
    val cid = eventuallySucceeds() {
      partyWalletClient.createTransferPreapproval() match {
        case CreateTransferPreapprovalResponse.Created(contractId) => contractId
        case CreateTransferPreapprovalResponse.AlreadyExists(contractId) => contractId
      }
    }
    // Ensure enough Scans have ingested it for it to be usable
    eventually() {
      checkValidator.lookupTransferPreapprovalByParty(
        PartyId.tryFromProtoPrimitive(partyWalletClient.userStatus().party)
      ) shouldBe defined
    }

    cid
  }

}

object WalletTestUtil {
  case class StaticUserRefs(
      validator: ValidatorAppBackendReference,
      wallet: WalletAppClientReference,
  )

  case class DynamicUserRefs(userParty: PartyId, static: StaticUserRefs) {
    def validator: ValidatorAppBackendReference = static.validator

    def wallet: WalletAppClientReference = static.wallet
  }

}
