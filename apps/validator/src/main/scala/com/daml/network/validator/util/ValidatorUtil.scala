package com.daml.network.validator.util

import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.CNNodeUtil
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

private[validator] object ValidatorUtil {

  def installWalletForUser(
      validatorServiceParty: PartyId,
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
      s"Installing wallet for endUserName:$endUserName, endUserParty=$endUserParty, validatorServiceParty=$validatorServiceParty, svcParty=$svcParty"
    )
    for {
      _ <- retryProvider.retryForClientCalls(
        "installWalletForUser",
        store.lookupWalletInstallByNameWithOffset(endUserName).flatMap {
          case result @ QueryResult(_, None) =>
            connection
              .submitCommands(
                actAs = Seq(validatorServiceParty, endUserParty),
                readAs = Seq.empty,
                commands = new walletCodegen.WalletAppInstall(
                  svcParty.toProtoPrimitive,
                  validatorServiceParty.toProtoPrimitive,
                  endUserName,
                  endUserParty.toProtoPrimitive,
                ).create.commands.asScala.toSeq,
                commandId = CNLedgerConnection
                  .CommandId(
                    "com.daml.network.validator.installWalletForUser",
                    Seq(validatorServiceParty),
                    CNLedgerConnection.sanitizeUserIdToPartyString(endUserName),
                  ),
                deduplicationOffset = result.deduplicationOffset,
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

  def offboard(
      endUserName: String,
      connection: CNLedgerConnection,
      store: ValidatorStore,
      validatorUserName: String,
      domainId: DomainId,
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    for {
      endUserParty <- connection.getPrimaryParty(endUserName)
      _ <- retryProvider.retryForClientCalls(
        "Remove install contract",
        store
          .lookupWalletInstallByNameWithOffset(endUserName)
          .map(_.value)
          .flatMap {
            case None =>
              logger.debug(s"No install contract found for user $endUserName, skipping")
              Future.unit
            case Some(c) =>
              connection.submitCommandsNoDedup(
                actAs = Seq(
                  store.key.validatorParty,
                  PartyId.tryFromProtoPrimitive(c.payload.endUserParty),
                ),
                readAs = Seq.empty,
                commands = c.contractId
                  .exerciseArchive(
                    new com.daml.network.codegen.java.da.internal.template.Archive()
                  )
                  .commands
                  .asScala
                  .toSeq,
                domainId = domainId,
              )
          },
        logger,
        // once the validator's actAs and readAs rights have been revoked,
        // this command could fail with PERMISSION_DENIED errors (#4425).
        additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
      )
      _ <- retryProvider.retryForClientCalls(
        "Remove validator right",
        store
          .lookupValidatorRightByPartyWithOffset(endUserParty)
          .map(_.value)
          .flatMap {
            case None =>
              logger.debug(s"No validator right found for user $endUserName, skipping")
              Future.unit
            case Some(c) =>
              connection.submitCommandsNoDedup(
                actAs = Seq(
                  store.key.validatorParty,
                  endUserParty,
                ),
                readAs = Seq.empty,
                commands = c.contractId
                  .exerciseArchive(
                    new com.daml.network.codegen.java.da.internal.template.Archive()
                  )
                  .commands
                  .asScala
                  .toSeq,
                domainId = domainId,
              )
          },
        logger,
        // once the validator's actAs and readAs rights have been revoked,
        // this command could fail with PERMISSION_DENIED errors (#4425).
        additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
      )
      _ <- connection.revokeUserRights(validatorUserName, Seq(endUserParty), Seq(endUserParty))
    } yield {
      logger.debug(s"User $endUserParty offboarded")
      ()
    }
  }
}
