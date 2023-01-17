package com.daml.network.validator.util

import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

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
      domainId: DomainId,
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
      // TODO(#713): remove this workaround for missing `read-as-any-party` rights
      _ <- connection.grantUserRights(walletServiceUser, Seq.empty, Seq(endUserParty))
      _ <- retryProvider.retryForAutomationGrpc(
        "installWalletForUser",
        store.lookupWalletInstallByNameWithOffset(endUserName).flatMap {
          case QueryResult(off, None) =>
            connection
              .submitCommands(
                actAs = Seq(validatorServiceParty, walletServiceParty, endUserParty),
                readAs = Seq.empty,
                commands = new walletCodegen.WalletAppInstall(
                  svcParty.toProtoPrimitive,
                  validatorServiceParty.toProtoPrimitive,
                  walletServiceParty.toProtoPrimitive,
                  endUserName,
                  endUserParty.toProtoPrimitive,
                ).create.commands.asScala.toSeq,
                // We dedup on the party rather than the username because the username can
                // have special characters not allowed in command ids.
                commandId = CoinLedgerConnection
                  .CommandId("com.daml.network.validator.installWalletForUser", Seq(endUserParty)),
                deduplicationOffset = off,
                domainId = domainId,
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
