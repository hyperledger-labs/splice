package com.daml.network.validator.util

import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.daml.ledger.javaapi.data.User
import com.daml.network.util.CoinUtil

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

  def onboard(
      endUserName: String,
      knownParty: Option[PartyId],
      connection: CoinLedgerConnection,
      store: ValidatorStore,
      validatorUserName: String,
      walletServiceUser: String,
      domainId: DomainId,
      retryProvider: CoinRetries,
      flagCloseable: FlagCloseable,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[PartyId] = {
    for {
      userPartyId <- knownParty match {
        case Some(party) =>
          connection.createUserWithPrimaryParty(
            endUserName,
            party,
            Seq(new User.Right.CanReadAs(store.key.validatorParty.toProtoPrimitive)),
          )
        case None =>
          connection.getOrAllocateParty(
            endUserName,
            Seq(new User.Right.CanReadAs(store.key.validatorParty.toProtoPrimitive)),
          )
      }
      _ <- connection.grantUserRights(validatorUserName, Seq(userPartyId), Seq.empty)
      _ <- installWalletForUser(
        endUserParty = userPartyId,
        endUserName = endUserName,
        walletServiceUser = walletServiceUser,
        walletServiceParty = store.key.walletServiceParty,
        validatorServiceParty = store.key.validatorParty,
        svcParty = store.key.svcParty,
        connection = connection,
        store = store,
        domainId = domainId,
        retryProvider = retryProvider,
        flagCloseable = flagCloseable,
        logger = logger,
      )
      // Create validator right contract so validator can collect validator rewards
      _ <- CoinUtil.createValidatorRight(
        user = userPartyId,
        validator = store.key.validatorParty,
        svc = store.key.svcParty,
        connection = connection,
        lookupValidatorRightByParty = store.lookupValidatorRightByPartyWithOffset,
        domainId = domainId,
        retryProvider = retryProvider,
        flagCloseable = flagCloseable,
        logger = logger,
      )
    } yield userPartyId
  }

}
