package com.daml.network.validator.util

import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.environment.{CNLedgerConnection, ParticipantAdminConnection, RetryProvider}
import com.daml.network.store.CNNodeAppStoreWithIngestion
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
      _ <- retryProvider.retryForClientCalls(
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
      participantAdminConnection: ParticipantAdminConnection,
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
            participantAdminConnection,
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
      validatorWalletUserName: Option[String],
      domainId: DomainId,
      retryProvider: RetryProvider,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    import com.daml.network.codegen.java.da.internal.template.Archive as CodegenArchive
    val store = storeWithIngestion.store
    val validatorParty = store.key.validatorParty
    for {
      endUserParty <- storeWithIngestion.connection.getPrimaryParty(endUserName)
      _ <-
        if (
          endUserParty == validatorParty ||
          endUserName == validatorUserName ||
          validatorWalletUserName.exists(_ == endUserName)
        ) {
          val msg = s"Tried to offboard the validator's user: $endUserName"
          logger.warn(msg)
          Future.failed(Status.INVALID_ARGUMENT.withDescription(msg).asRuntimeException())
        } else {
          Future.unit
        }
      _ <- retryProvider.retryForClientCalls(
        "Remove install contract and validator right",
        Future
          .sequence(
            Seq(
              store
                .lookupWalletInstallByNameWithOffset(endUserName)
                .map(_.value.map(_.contractId.exerciseArchive(new CodegenArchive())).orElse {
                  logger.debug(s"No install contract found for user $endUserName, skipping")
                  None
                }),
              store
                .lookupValidatorRightByPartyWithOffset(endUserParty)
                .map(_.value.map(_.contractId.exerciseArchive(new CodegenArchive())).orElse {
                  logger.debug(s"No validator right found for user $endUserName, skipping")
                  None
                }),
            )
          )
          .flatMap(_.collect { case Some(archive) => archive } match {
            case Seq() => Future.unit
            case archives =>
              storeWithIngestion.connection
                .submitCommandsNoDedup(
                  actAs = Seq(
                    store.key.validatorParty,
                    endUserParty,
                  ),
                  readAs = Seq.empty,
                  commands = archives,
                  domainId = domainId,
                )
          }),
        logger,
        // once the validator's actAs and readAs rights have been revoked,
        // these commands could fail with PERMISSION_DENIED errors (#4425).
        additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
      )
    } yield {
      logger.debug(s"User $endUserParty offboarded")
      ()
    }
  }
}
