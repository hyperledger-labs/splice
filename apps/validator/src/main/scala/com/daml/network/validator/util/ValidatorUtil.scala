package com.daml.network.validator.util

import com.daml.network.codegen.CN.Wallet as walletCodegen
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

private[validator] object ValidatorUtil {

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
      flagCloseable: FlagCloseable,
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
      _ <- retryProvider.retryForAutomationWithUncleanShutdown(
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
        flagCloseable,
      )
    } yield ()
  }
}
