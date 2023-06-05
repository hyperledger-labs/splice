package com.daml.network.validator.util

import com.daml.network.store.CNNodeAppStoreWithIngestion
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
      storeWithIngestion: CNNodeAppStoreWithIngestion[ValidatorStore],
      domainId: DomainId,
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Unit] = {
    val store = storeWithIngestion.store
    logger.debug(
      s"Installing wallet for endUserName:$endUserName, endUserParty=$endUserParty, validatorServiceParty=$validatorServiceParty, svcParty=$svcParty"
    )
    for {
      // TODO (#5366) use `retryForClientCalls` here again once Canton X submissions work more robustly
      _ <- retryProvider.retryForAutomation(
        "installWalletForUser",
        store.lookupWalletInstallByNameWithOffset(endUserName).flatMap {
          case QueryResult(offset, None) =>
            storeWithIngestion.connection
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
                deduplicationOffset = offset,
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
      storeWithIngestion: CNNodeAppStoreWithIngestion[ValidatorStore],
      validatorUserName: String,
      domainId: DomainId,
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[PartyId] = {
    val store = storeWithIngestion.store
    for {
      userPartyId <- knownParty match {
        case Some(party) =>
          storeWithIngestion.connection.createUserWithPrimaryParty(
            endUserName,
            party,
            Seq(),
          )
        case None =>
          storeWithIngestion.connection.getOrAllocateParty(
            endUserName,
            Seq(),
          )
      }
      _ <- storeWithIngestion.connection.grantUserRights(
        validatorUserName,
        Seq(userPartyId),
        Seq.empty,
      )
      _ <- installWalletForUser(
        endUserParty = userPartyId,
        endUserName = endUserName,
        validatorServiceParty = store.key.validatorParty,
        svcParty = store.key.svcParty,
        storeWithIngestion = storeWithIngestion,
        domainId = domainId,
        retryProvider = retryProvider,
        logger = logger,
      )
      // Create validator right contract so validator can collect validator rewards
      _ <- CNNodeUtil.createValidatorRight(
        user = userPartyId,
        validator = store.key.validatorParty,
        svc = store.key.svcParty,
        connection = storeWithIngestion.connection,
        lookupValidatorRightByParty =
          storeWithIngestion.store.lookupValidatorRightByPartyWithOffset,
        domainId = domainId,
        retryProvider = retryProvider,
        logger = logger,
      )
    } yield userPartyId
  }

  def offboard(
      endUserName: String,
      storeWithIngestion: CNNodeAppStoreWithIngestion[ValidatorStore],
      validatorUserName: String,
      domainId: DomainId,
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    val store = storeWithIngestion.store
    for {
      endUserParty <- storeWithIngestion.connection.getPrimaryParty(endUserName)
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
              storeWithIngestion.connection
                .submitWithResultAndOffsetNoDedup(
                  actAs = Seq(
                    store.key.validatorParty,
                    PartyId.tryFromProtoPrimitive(c.payload.endUserParty),
                  ),
                  readAs = Seq.empty,
                  update = c.contractId.exerciseArchive(
                    new com.daml.network.codegen.java.da.internal.template.Archive()
                  ),
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
              storeWithIngestion.connection
                .submitWithResultAndOffsetNoDedup(
                  actAs = Seq(
                    store.key.validatorParty,
                    endUserParty,
                  ),
                  readAs = Seq.empty,
                  update = c.contractId
                    .exerciseArchive(
                      new com.daml.network.codegen.java.da.internal.template.Archive()
                    ),
                  domainId = domainId,
                )
          },
        logger,
        // once the validator's actAs and readAs rights have been revoked,
        // this command could fail with PERMISSION_DENIED errors (#4425).
        additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
      )
      _ <- storeWithIngestion.connection.revokeUserRights(
        validatorUserName,
        Seq(endUserParty),
        Seq(endUserParty),
      )
    } yield {
      logger.debug(s"User $endUserParty offboarded")
      ()
    }
  }
}
