package com.daml.network.util

import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{
  CoinRemoteParticipantReference,
  LedgerApiUtils,
  ValidatorAppReference,
  WalletAppBackendReference,
  WalletAppClientReference,
  WalletAppReference,
}
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{CoinUtil, CommonCoinAppInstanceReferences, Proto}
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId

import java.time.Duration
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait WalletTestUtil extends CoinIntegrationTest {
  this: CommonCoinAppInstanceReferences =>

  /** @param expectedQuantityRanges : lower and upper bounds for coins sorted by their initial quantity in ascending order. */
  def checkWallet(
      walletParty: PartyId,
      wallet: WalletAppReference,
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
      expectedUQRange: (BigDecimal, BigDecimal),
      expectedLQRange: (BigDecimal, BigDecimal),
      expectedHRange: (BigDecimal, BigDecimal),
  ): Unit = clue(s"Checking balance in round $expectedRound") {
    eventually() {
      val balance = wallet.balance()
      balance.round shouldBe expectedRound
      assertInRange(balance.unlockedQty, expectedUQRange)
      assertInRange(balance.lockedQty, expectedLQRange)
      assertInRange(balance.holdingFees, expectedHRange)
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
      walletCodegen.AppPaymentRequest.ContractId,
      walletCodegen.AppPaymentRequest,
  ) = {
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
      val reqC = new walletCodegen.AppPaymentRequest(
        userParty.toProtoPrimitive,
        Seq(
          new walletCodegen.ReceiverQuantity(
            userParty.toProtoPrimitive,
            new walletCodegen.PaymentQuantity(
              BigDecimal(10).bigDecimal.setScale(10),
              walletCodegen.Currency.CC,
            ),
          )
        ).asJava,
        userParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        java.time.Instant.now().plus(1, ChronoUnit.MINUTES),
        new RelTime(60 * 1000000),
        referenceId.toInterface(walletCodegen.DeliveryOffer.INTERFACE),
      )
      val result = LedgerApiUtils.submitWithResult(
        remoteParticipant,
        userId = userId,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = reqC.create,
      )
      val cid = walletCodegen.AppPaymentRequest.COMPANION.toContractId(result.contractId)
      (cid, reqC)
    }

    (referenceId, reqCid, reqC)
  }
}
