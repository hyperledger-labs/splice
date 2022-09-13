package com.daml.network.wallet.util

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.Primitive
import com.daml.network.util.UploadablePackage
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.participant.ledger.api.client.{DecodeUtil, LedgerConnection}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

object WalletUtil extends UploadablePackage {
  lazy val walletTemplateId: com.daml.ledger.api.v1.value.Identifier =
    ApiTypes.TemplateId.unwrap(walletCodegen.AppPaymentRequest.id)

  lazy val packageId: String = walletTemplateId.packageId

  // See `Compile / resourceGenerators` in build.sbt
  lazy val resourcePath: String = "dar/wallet-0.1.0.dar"

  def geWalletApp(
      serviceParty: PartyId,
      connection: CoinLedgerConnection,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Option[Primitive.ContractId[walletCodegen.WalletApp]]] = {
    connection
      .activeContracts(
        LedgerConnection.transactionFilterByParty(
          Map(serviceParty -> Seq(walletCodegen.WalletApp.id))
        )
      )
      .map(_.flatMap(DecodeUtil.decodeCreated(walletCodegen.WalletApp)))
      .map(_.headOption.map(_.contractId))
      .recover {
        case e if e.getMessage.contains("Templates do not exist") =>
          logger.debug(
            "Wallet daml model not uploaded, can not find WalletApp contracts. " +
              "This should only happen if you onboard a user onto a deployment that has no wallet app."
          )
          None
      }
  }

  def installWalletForUser(
      endUserParty: PartyId,
      validatorServiceParty: PartyId,
      svcParty: PartyId,
      connection: CoinLedgerConnection,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Unit] = {
    for {
      appCidO <- geWalletApp(validatorServiceParty, connection, logger)
      _ <- appCidO match {
        case None =>
          logger.debug(
            s"No WalletApp contract visible for validator $validatorServiceParty. Skipping installing the wallet for user $endUserParty. " +
              "This should only happen if you onboard a user onto a deployment that has no wallet app."
          )
          Future.successful(())
        case Some(appCid) =>
          logger.debug(
            s"Installing wallet for endUserParty=$endUserParty, validatorServiceParty=$validatorServiceParty, svcParty=$svcParty."
          )
          connection
            .submitCommand(
              Seq(endUserParty, validatorServiceParty),
              Seq(),
              Seq(
                appCid
                  .exerciseInstall(
                    endUser = endUserParty.toPrim,
                    svcUser = svcParty.toPrim,
                  )
                  .command
              ),
            )
            .recover {
              // TODO (i751): use self-service error codes
              case e if e.getMessage.contains("DUPLICATE_CONTRACT_KEY") =>
                logger.debug(
                  s"Installing the wallet for user $endUserParty resulted in duplicate contract key." +
                    "This probably means the wallet was already installed before, ignoring the error."
                )
                ()
            }
      }
    } yield ()
  }

  def initializeWalletApp(
      walletServiceParty: PartyId,
      validatorServiceParty: PartyId,
      connection: CoinLedgerConnection,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Unit] = {
    logger.debug(
      s"Initializing wallet app for walletServiceParty=$walletServiceParty, validatorServiceParty=$validatorServiceParty"
    )
    connection
      .submitWithResult(
        Seq(walletServiceParty, validatorServiceParty),
        Seq(),
        walletCodegen
          .WalletApp(walletServiceParty.toPrim, validatorServiceParty.toPrim)
          .create,
      )
      .map(_ => ())
      .recover {
        // TODO (i751): use self-service error codes
        case e if e.getMessage.contains("DUPLICATE_CONTRACT_KEY") =>
          logger.debug(
            s"Initializing the wallet app for service party $walletServiceParty resulted in duplicate contract key." +
              "This probably means the wallet app was already initialized before, ignoring the error."
          )
          ()
      }
  }

}
