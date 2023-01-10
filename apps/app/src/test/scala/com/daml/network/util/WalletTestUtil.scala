package com.daml.network.util

import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.wallet.{
  payment as paymentCodegen,
  subscriptions as subsCodegen,
}
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{
  CoinRemoteParticipantReference,
  LedgerApiUtils,
  RemoteDirectoryAppReference,
  ValidatorAppBackendReference,
  ValidatorAppReference,
  WalletAppBackendReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CoinTests.{CoinTestCommon, CoinTestConsoleEnvironment}
import com.daml.network.util.{CoinUtil, CommonCoinAppInstanceReferences, Proto}
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait WalletTestUtil extends CoinTestCommon {
  this: CommonCoinAppInstanceReferences =>

  val exactly = (x: BigDecimal) => (x, x)

  /** @param expectedQuantityRanges : lower and upper bounds for coins sorted by their initial quantity in ascending order. */
  def checkWallet(
      walletParty: PartyId,
      wallet: WalletAppClientReference,
      expectedQuantityRanges: Seq[(BigDecimal, BigDecimal)],
  ): Unit = clue(s"checking wallet with $expectedQuantityRanges") {
    eventually(10.seconds, 500.millis) {
      val coins =
        wallet.list().coins.sortBy(coin => coin.contract.payload.quantity.initialQuantity)
      coins should have size (expectedQuantityRanges.size.toLong)
      coins
        .zip(expectedQuantityRanges)
        .foreach { case (coin, quantityBounds) =>
          coin.contract.payload.owner shouldBe walletParty.toPrim
          val coinQuantity =
            coin.contract.payload.quantity
          assertInRange(coinQuantity.initialQuantity, quantityBounds)
          coinQuantity.ratePerRound shouldBe
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

  def lockCoins(
      userWallet: WalletAppBackendReference,
      userParty: PartyId,
      validatorParty: PartyId,
      coins: Seq[GrpcWalletAppClient.CoinPosition],
      quantity: Int,
      transferContext: v1.coin.AppTransferContext,
  ): Unit = clue(s"Locking $quantity coins for $userParty") {
    val coinOpt = coins.find(_.effectiveQuantity >= quantity)
    val expirationOpt = Proto.decode(Proto.Timestamp)(
      20000000000000000L // Wed May 18 2033
    )

    (coinOpt, expirationOpt) match {
      case (Some(coin), Right(expiration)) => {
        userWallet.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
          Seq(userParty, validatorParty),
          optTimeout = None,
          commands = transferContext.coinRules
            .exerciseCoinRules_Transfer(
              new v1.coin.Transfer(
                userParty.toProtoPrimitive,
                userParty.toProtoPrimitive,
                Seq[v1.coin.TransferInput](
                  new v1.coin.transferinput.InputCoin(
                    coin.contract.contractId.toInterface(v1.coin.Coin.INTERFACE)
                  )
                ).asJava,
                Seq[v1.coin.TransferOutput](
                  new v1.coin.transferoutput.OutputSenderCoin(
                    Some(BigDecimal(quantity).bigDecimal).toJava,
                    Some(
                      new v1.coin.TimeLock(
                        Seq(userParty.toProtoPrimitive).asJava,
                        expiration.toInstant,
                      )
                    ).toJava,
                  ),
                  new v1.coin.transferoutput.OutputSenderCoin(
                    None.toJava,
                    None.toJava,
                  ),
                ).asJava,
                "lock coins",
              ),
              new v1.coin.TransferContext(
                transferContext.openMiningRound,
                Map.empty[v1.round.Round, v1.round.IssuingMiningRound.ContractId].asJava,
                Map.empty[String, v1.coin.ValidatorRight.ContractId].asJava,
                // note: we don't provide a featured app right as sender == provider
                None.toJava,
              ),
            )
            .commands
            .asScala
            .toSeq,
        )
      }
      case _ => {
        coinOpt shouldBe a[Some[_]]
        expirationOpt shouldBe a[Right[_, _]]
      }
    }
  }

  /** Onboards the daml user associated with the given wallet app user reference
    * onto the given validator, and waits until the wallet is usable for that user
    */
  def onboardWalletUser(
      walletAppClient: WalletAppClientReference,
      validator: ValidatorAppReference,
  ): PartyId = {
    val damlUser = walletAppClient.config.damlUser

    clue(s"Onboard $damlUser on ${validator.name}") {
      val party = validator.onboardUser(damlUser)
      // The wallet is not immediately usable by the onboarded user -
      // the wallet app backend has to ingest the wallet install contract first.
      eventually() {
        walletAppClient.userStatus().userOnboarded shouldBe true
      }
      party
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
      senderTransferFeeRatio: BigDecimal = 1.0,
  ) = {
    val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(1))
    val transferOfferId =
      senderWallet.createTransferOffer(
        receiver,
        amount,
        "test transfer",
        expiration,
        idempotencyKey = UUID.randomUUID.toString,
        senderTransferFeeRatio,
      )
    eventually() {
      receiverWallet.listTransferOffers() should have size 1
    }
    receiverWallet.acceptTransferOffer(transferOfferId)
    // note that something like `receiverWallet.listAcceptedTransferOffers() should have size 1`
    // is potentially racy (possible to circumvent this by being clever, but we chose the simple solution for now)
  }

  def createSelfPaymentRequest(
      remoteParticipant: CoinRemoteParticipantReference,
      userId: String,
      userParty: PartyId,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): (
      testWalletCodegen.TestDeliveryOffer.ContractId,
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
  ) = {
    val now = env.environment.clock.now
    val referenceId = clue(s"Create test delivery offer for $userParty") {
      val result = LedgerApiUtils.submitWithResult(
        remoteParticipant,
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = new testWalletCodegen.TestDeliveryOffer(
          scan.getSvcPartyId().toProtoPrimitive,
          userParty.toProtoPrimitive,
          "description",
        ).create,
      )
      testWalletCodegen.TestDeliveryOffer.COMPANION.toContractId(result.contractId)
    }

    val (reqCid, reqC) = clue(s"Create payment request for $userParty to self") {
      val reqC = new paymentCodegen.AppPaymentRequest(
        userParty.toProtoPrimitive,
        Seq(
          new paymentCodegen.ReceiverQuantity(
            userParty.toProtoPrimitive,
            new paymentCodegen.PaymentQuantity(
              BigDecimal(10).bigDecimal.setScale(10),
              paymentCodegen.Currency.CC,
            ),
          )
        ).asJava,
        userParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        now.plus(Duration.ofMinutes(1)).toInstant,
        new RelTime(60 * 1000000),
        referenceId.toInterface(paymentCodegen.DeliveryOffer.INTERFACE),
      )
      val result = LedgerApiUtils.submitWithResult(
        remoteParticipant,
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = reqC.create,
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
    directory.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
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
      directory.ledgerApi.ledger_api.acs
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
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = deliveryOffer.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.acs
        .awaitJava(testWalletCodegen.TestDeliveryOffer.COMPANION)(
          aliceUserParty,
          _.data == deliveryOffer,
        )
        .id
    }
  }

  def receiverQuantity(
      receiverParty: PartyId,
      quantity: Int,
      currency: paymentCodegen.Currency,
  ) =
    new paymentCodegen.ReceiverQuantity(
      receiverParty.toProtoPrimitive,
      new paymentCodegen.PaymentQuantity(
        BigDecimal(quantity).bigDecimal,
        currency,
      ),
    )

  def createPaymentRequest(
      aliceUserParty: PartyId,
      receiverQuantities: Seq[paymentCodegen.ReceiverQuantity],
  )(implicit env: CoinTestConsoleEnvironment) = {
    val deliveryOfferId = createTestDeliveryOffer(aliceUserParty)

    clue("Create a payment request") {
      val paymentRequest = new paymentCodegen.AppPaymentRequest(
        aliceUserParty.toProtoPrimitive,
        receiverQuantities.asJava,
        aliceUserParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        Instant.now().plus(5, ChronoUnit.MINUTES), // expires in 5 min
        new RelTime(5 * 60 * 1000000L), // 5min collection duration.
        deliveryOfferId.toInterface(paymentCodegen.DeliveryOffer.INTERFACE),
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = paymentRequest.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.acs
        .awaitJava(paymentCodegen.AppPaymentRequest.COMPANION)(aliceUserParty)
        .id
    }
  }

  def createSelfPaymentRequest(
      aliceUserParty: PartyId,
      quantity: Int,
      currency: paymentCodegen.Currency,
  )(implicit env: CoinTestConsoleEnvironment) = {
    val receiverQuantities = Seq(
      receiverQuantity(aliceUserParty, quantity, currency)
    )

    createPaymentRequest(aliceUserParty, receiverQuantities)
  }

  private val defaultSubscriptionQuantity = new paymentCodegen.PaymentQuantity(
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
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = context.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.acs
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
      quantity: paymentCodegen.PaymentQuantity,
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
      quantity,
      new RelTime(60 * 60 * 1000000L),
      new RelTime(10 * 60 * 1000000L),
      new RelTime(60 * 1000000L),
    )
    (subscription, payData)
  }

  protected def createSelfSubscriptionRequest(
      aliceUserParty: PartyId,
      nextPaymentDueAt: Instant,
      quantity: paymentCodegen.PaymentQuantity,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val contextId = createSelfSubscriptionContext(aliceUserParty)
    val (subscription, payData) =
      createSelfSubscriptionData(contextId, aliceUserParty, nextPaymentDueAt, quantity)
    clue("Create subscription request") {
      val subscriptionRequest = new subsCodegen.SubscriptionRequest(
        subscription,
        payData,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = subscriptionRequest.create.commands.asScala.toSeq,
      )
    }
  }

  protected def createSelfSubscription(
      aliceUserParty: PartyId,
      nextPaymentDueAt: Instant,
      quantity: paymentCodegen.PaymentQuantity = defaultSubscriptionQuantity,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val contextId = createSelfSubscriptionContext(aliceUserParty)
    val (subscriptionData, payData) =
      createSelfSubscriptionData(contextId, aliceUserParty, nextPaymentDueAt, quantity)
    val subscriptionId = clue("Create a subscription") {
      val subscription = new subsCodegen.Subscription(
        aliceUserParty.toProtoPrimitive,
        aliceUserParty.toProtoPrimitive,
        aliceUserParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        contextId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = subscription.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.acs
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
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = state.create.commands.asScala.toSeq,
      )
    }
  }
}
