package com.daml.network.validator.util

import com.daml.network.codegen.CC.{Coin => coinCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

private[validator] object ValidatorUtil {
  def createValidatorRight(
      user: PartyId,
      validator: PartyId,
      svc: PartyId,
      connection: CoinLedgerConnection,
      store: ValidatorStore,
      retryProvider: CoinRetries,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Unit] =
    retryProvider.retry(
      "createValidatorRight",
      store.lookupValidatorRightByParty(user).flatMap {
        case QueryResult(off, None) =>
          // TODO(#790) Switch to the generalized version of mkCommandId once it has been added
          val commandId = s"com.daml.network.validator.ValidatorRight_$user"
          connection
            .submitCommandWithDedup(
              actAs = Seq(validator, user),
              readAs = Seq.empty,
              command = Seq(
                coinCodegen
                  .ValidatorRight(
                    svc = svc.toPrim,
                    user = user.toPrim,
                    validator = validator.toPrim,
                  )
                  .create
                  .command
              ),
              commandId = commandId,
              deduplicationOffset = off,
            )
            .map(_ => ())
        case QueryResult(_, Some(_)) =>
          logger.info(s"ValidatorRight for $user already exists, skipping")
          Future.successful(())
      },
    )

  def installWalletForUser(
      validatorServiceParty: PartyId,
      walletServiceUser: String,
      walletServiceParty: PartyId,
      endUserParty: PartyId,
      endUserName: String,
      svcParty: PartyId,
      connection: CoinLedgerConnection,
      store: ValidatorStore,
      retryProvider: CoinRetries,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Unit] = {
    logger.debug(
      s"Installing wallet for endUserParty=$endUserParty, walletServiceParty=$walletServiceParty, validatorServiceParty=$validatorServiceParty, svcParty=$svcParty"
    )
    for {
      // TODO (i713): remove this workaround for missing `read-as-any-party` rights
      _ <- connection.grantUserRights(walletServiceUser, Seq.empty, Seq(endUserParty))
      _ <- retryProvider.retry(
        "installWalletForUser",
        store.lookupWalletInstallByName(endUserName).flatMap {
          case QueryResult(off, None) =>
            // TODO(#790) Switch to the generalized version of mkCommandId once it has been added
            // We dedup on the party rather than the username because the username can
            // have special characters not allowed in command ids.
            val commandId = s"com.daml.network.validator.WalletAppInstall_$endUserParty"
            connection
              .submitCommandWithDedup(
                actAs = Seq(validatorServiceParty, walletServiceParty, endUserParty),
                readAs = Seq.empty,
                command = Seq(
                  walletCodegen
                    .WalletAppInstall(
                      walletServiceParty = walletServiceParty.toPrim,
                      svcParty = svcParty.toPrim,
                      validatorParty = validatorServiceParty.toPrim,
                      endUserParty = endUserParty.toPrim,
                      endUserName = endUserName,
                    )
                    .create
                    .command
                ),
                commandId = commandId,
                deduplicationOffset = off,
              )
          case QueryResult(_, Some(_)) =>
            logger.info(s"WalletAppInstall for $endUserName already exists, skipping")
            Future.successful(())
        },
      )
    } yield ()
  }
}
