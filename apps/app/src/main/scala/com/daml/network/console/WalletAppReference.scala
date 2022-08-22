package com.daml.network.console

import com.daml.ledger.client.binding.Primitive
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.util.{Contract, Value}
import com.daml.network.wallet.admin.api.client.commands.WalletAppCommands
import com.daml.network.wallet.config.{LocalWalletAppConfig, RemoteWalletAppConfig}
import com.digitalasset.canton.console.{
  BaseInspection,
  ConsoleCommandResult,
  GrpcRemoteInstanceReference,
  Help,
  LocalInstanceReference,
}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CC.{Coin => coinCodegen, CoinRules => coinRulesCodegen}
import com.digitalasset.network.CN.Wallet.PaymentChannel
import com.digitalasset.network.CN.{Wallet => walletCodegen}

abstract class WalletAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name) {

  @Help.Summary("List all coins associated with the configured user")
  @Help.Description(
    "Queries the configured remote participant for the Coins owned by the configured user. " +
      "Returns all found coins."
  )
  def list(): Seq[Contract[coinCodegen.Coin]] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.List())
    }
  }

  def initialize(validator: PartyId): Unit = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.Initialize(validator))
    }
  }

  @Help.Summary("Credits the requested quantity of Canton coin to the wallet's user")
  @Help.Description(
    "This function will only be available in the testnet. It allows creating coins for testing purposes." +
      "Returns the contract ID of the created contract. "
  )
  def tap(quantity: BigDecimal): Primitive.ContractId[coinCodegen.Coin] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.Tap(quantity))
    }
  }

  @Help.Summary("List all payment requests of the configured user")
  @Help.Description(
    "Queries the configured remote participant for the PaymentRequests of the configured user. " +
      "Returns all found payment requests."
  )
  def listAppPaymentRequests(): Seq[Contract[walletCodegen.AppPaymentRequest]] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ListAppPaymentRequests())
    }
  }

  @Help.Summary("Accept a payment request")
  @Help.Description(
    "Accept a payment request and deliver the coin to be locked into the accepted payment." +
      " Returns the contract ID of the accepted payment."
  )
  def acceptAppPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.AppPaymentRequest],
      coinId: Primitive.ContractId[coinCodegen.Coin],
  ): Primitive.ContractId[walletCodegen.AcceptedAppPayment] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.AcceptAppPaymentRequest(requestId, coinId))
    }
  }

  @Help.Summary("Reject a payment request")
  @Help.Description(
    "Reject a payment request."
  )
  def rejectAppPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.AppPaymentRequest]
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.RejectAppPaymentRequest(requestId))
    }
  }

  @Help.Summary("Propose the creation of a payment channel")
  @Help.Description(
    "Propose the creation of a uni-directional payment channel with a specific receiver." +
      " Returns the contract-id of the created proposal."
  )
  def proposePaymentChannel(
      receiver: PartyId,
      replacesChannelId: Option[Primitive.ContractId[PaymentChannel]] = None,
      allowRequests: Boolean = true,
      allowOffers: Boolean = true,
      allowDirectTransfers: Boolean = true,
      senderTransferFeeRatio: Double = 1.0,
  ): Primitive.ContractId[walletCodegen.PaymentChannelProposal] = {
    consoleEnvironment.run {
      adminCommand(
        WalletAppCommands.ProposePaymentChannel(
          receiver,
          replacesChannelId,
          allowRequests,
          allowOffers,
          allowDirectTransfers,
          senderTransferFeeRatio,
        )
      )
    }
  }

  @Help.Summary("List active payment channel proposals")
  @Help.Description(
    "Shows both incoming and outgoing payment channel proposals."
  )
  def listPaymentChannelProposals(): Seq[Contract[walletCodegen.PaymentChannelProposal]] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ListPaymentChannelProposals())
    }
  }

  @Help.Summary("List payment channels")
  @Help.Description(
    "Shows payment channels where the user is either the sender or the receiver"
  )
  def listPaymentChannels(): Seq[Contract[walletCodegen.PaymentChannel]] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ListPaymentChannels())
    }
  }

  @Help.Summary("Accept a payment channel proposal.")
  @Help.Description(
    "Accept a specific payment channel proposal."
  )
  def acceptPaymentChannelProposal(
      proposalId: Primitive.ContractId[walletCodegen.PaymentChannelProposal]
  ): Primitive.ContractId[walletCodegen.PaymentChannel] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.AcceptPaymentChannelProposal(proposalId))
    }
  }

  @Help.Summary("Execute a direct transfer over a payment channel")
  @Help.Description(
    "Assumes that the payment channel for the given receiver already exists."
  )
  def executeDirectTransfer(
      receiver: PartyId,
      quantity: BigDecimal,
      coinId: Primitive.ContractId[coinCodegen.Coin],
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ExecuteDirectTransfer(receiver, quantity, coinId))
    }
  }

  @Help.Summary("Request a payment through a payment channel")
  @Help.Description(
    "Assumes that the payment channel for the given sender already exists."
  )
  def createOnChannelPaymentRequest(
      sender: PartyId,
      quantity: BigDecimal,
      description: String,
  ): Primitive.ContractId[walletCodegen.OnChannelPaymentRequest] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.CreateOnChannelPaymentRequest(sender, quantity, description))
    }
  }

  @Help.Summary("List all on-channel payment requests of the configured user")
  @Help.Description(
    "Shows all incoming and outgoing payment requests over payment channels."
  )
  def listOnChannelPaymentRequests(): Seq[Contract[walletCodegen.OnChannelPaymentRequest]] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ListOnChannelPaymentRequests())
    }
  }

  @Help.Summary("Accept a request for payment through a payment channel")
  @Help.Description(
    "Accepts the request using the given coin."
  )
  def acceptOnChannelPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest],
      coinId: Primitive.ContractId[coinCodegen.Coin],
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.AcceptOnChannelPaymentRequest(requestId, coinId))
    }
  }

  @Help.Summary("Reject a request for payment through a payment channel")
  @Help.Description(
    "Rejects the request."
  )
  def rejectOnChannelPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest]
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.RejectOnChannelPaymentRequest(requestId))
    }
  }

  @Help.Summary("Withdraw a request for payment through a payment channel")
  @Help.Description(
    "Withdraws the request."
  )
  def withdrawOnChannelPaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.OnChannelPaymentRequest]
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.WithdrawOnChannelPaymentRequest(requestId))
    }
  }

  @Help.Summary("List app rewards")
  @Help.Description("List all open app rewards for the configured user")
  def listAppRewards(): Seq[Contract[coinCodegen.AppReward]] =
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ListAppRewards())
    }

  @Help.Summary("List validator rewards")
  @Help.Description(
    "List all open validator rewards for the configured user based on the active ValidatorRights"
  )
  def listValidatorRewards(): Seq[Contract[coinCodegen.ValidatorReward]] =
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ListValidatorRewards())
    }

  @Help.Summary("Collect rewards")
  @Help.Description(
    "Merge all currently open app and validator rewards for the given round with the given coin"
  )
  def collectRewards(
      coinId: Primitive.ContractId[coinCodegen.Coin],
      round: Long,
  ): Primitive.ContractId[coinCodegen.Coin] =
    consoleEnvironment.run {
      ConsoleCommandResult.fromEither {
        for {
          validatorRewards <- adminCommand(WalletAppCommands.ListValidatorRewards()).toEither
          validatorRewardInputs = validatorRewards
            .filter(c => c.payload.round.number == round)
            .map(c => coinRulesCodegen.TransferInput.InputValidatorReward(c.contractId))
          appRewards <- adminCommand(WalletAppCommands.ListAppRewards()).toEither
          appRewardInputs = appRewards
            .filter(c => c.payload.round.number == round)
            .map(c => coinRulesCodegen.TransferInput.InputAppReward(c.contractId))
          inputCoin = coinRulesCodegen.TransferInput.InputCoin(coinId)
          inputs = (inputCoin +: validatorRewardInputs :++ appRewardInputs).map(Value(_))
          outputs = Seq(WalletAppCommands.RedistributeOutput(exactQuantity = None))
          coins <- adminCommand(
            WalletAppCommands.Redistribute(inputs = inputs, outputs = outputs)
          ).toEither
          _ <-
            if (coins.size == 1) Right(())
            else
              Left(
                s"Expected exactly one coin as a result of Redistribute but got ${coins.size} coins $coins"
              )
        } yield coins(0)
      }
    }

  @Help.Summary("List all transfer requests the user is a sender on")
  def listTransferRequests(): Seq[Contract[walletCodegen.TransferRequest]] =
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ListTransferRequests())
    }

  @Help.Summary("Accept the transfer request by transferring the given coin")
  def acceptTransferRequest(
      request: Primitive.ContractId[walletCodegen.TransferRequest],
      coin: Primitive.ContractId[coinCodegen.Coin],
  ): Primitive.ContractId[walletCodegen.TransferReceipt] =
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.AcceptTransferRequest(request, coin))
    }

  @Help.Summary("List all transfer receipts the user is a sender on")
  def listTransferReceipts(): Seq[Contract[walletCodegen.TransferReceipt]] =
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ListTransferReceipts())
    }
}

class RemoteWalletAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends WalletAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Remote wallet"

  @Help.Summary("Return remote wallet config")
  def config: RemoteWalletAppConfig =
    consoleEnvironment.environment.config.remoteWalletsByString(name)

}

/** Single local Wallet app reference. Defines the console commands that can be run against a local Wallet
  * app reference.
  */
class LocalWalletAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends WalletAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Wallet"

  protected val nodes = consoleEnvironment.environment.wallets

  @Help.Summary("Return wallet app config")
  def config: LocalWalletAppConfig =
    consoleEnvironment.environment.config.walletsByString(name)

  /** Remote participant this Wallet app is configured to interact with. */
  val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
