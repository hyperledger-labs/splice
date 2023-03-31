package com.daml.network.validator.util

import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.CNNodeUtil
import com.daml.network.validator.store.ValidatorStore
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
      connection: CNLedgerConnection,
      store: ValidatorStore,
      domainId: DomainId,
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Unit] = {
    logger.debug(
      s"Installing wallet for endUserName:$endUserName, endUserParty=$endUserParty, walletServiceParty=$walletServiceParty, validatorServiceParty=$validatorServiceParty, svcParty=$svcParty"
    )
    for {
      _ <- connection.grantUserRights(walletServiceUser, Seq.empty, Seq(endUserParty))
      _ <- retryProvider.retryForAutomation(
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
                commandId = CNLedgerConnection
                  .CommandId(
                    "com.daml.network.validator.installWalletForUser",
                    Seq(validatorServiceParty),
                    CNLedgerConnection.sanitizeUserIdToPartyString(endUserName),
                  ),
                deduplicationOffset = off,
                domainId = domainId,
              )
          case QueryResult(_, Some(_)) =>
            logger.info(s"WalletAppInstall for $endUserName already exists, skipping")
            Future.successful(())
        },
        logger,
      )
    } yield ()
  }

  def onboard(
      endUserName: String,
      knownParty: Option[PartyId],
      connection: CNLedgerConnection,
      store: ValidatorStore,
      validatorUserName: String,
      walletServiceUser: String,
      domainId: DomainId,
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[PartyId] = {
    for {
      userPartyId <- knownParty match {
        case Some(party) =>
          connection.createUserWithPrimaryParty(
            endUserName,
            party,
            Seq(),
          )
        case None =>
          connection.getOrAllocateParty(
            endUserName,
            Seq(),
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
        logger = logger,
      )
      // Create validator right contract so validator can collect validator rewards
      _ <- CNNodeUtil.createValidatorRight(
        user = userPartyId,
        validator = store.key.validatorParty,
        svc = store.key.svcParty,
        connection = connection,
        lookupValidatorRightByParty = store.lookupValidatorRightByPartyWithOffset,
        domainId = domainId,
        retryProvider = retryProvider,
        logger = logger,
      )
    } yield userPartyId
  }
}
