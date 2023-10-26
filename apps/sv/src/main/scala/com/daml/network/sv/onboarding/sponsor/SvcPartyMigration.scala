package com.daml.network.sv.onboarding.sponsor

import cats.data.EitherT
import cats.syntax.foldable.*
import com.daml.error.utils.ErrorDetails
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.sv.onboarding.SvcPartyHosting
import com.daml.network.sv.onboarding.SvcPartyHosting.SvcPartyMigrationFailure
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.digitalasset.canton.LfTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.ByteString
import io.grpc.StatusRuntimeException

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}

class SvcPartyMigration(
    globalDomain: DomainId,
    svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
    svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    svcPartyHosting: SvcPartyHosting,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  private val svcStore = svcStoreWithIngestion.store
  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty
  private val partyHosting = new SponsorSvcPartyHosting(
    participantAdminConnection,
    svcParty,
    svcPartyHosting,
    loggerFactory,
  )

  def authorizeParticipantForHostingSvcParty(
      participantId: ParticipantId
  )(implicit tc: TraceContext): EitherT[Future, SvcPartyMigrationFailure, ByteString] = {
    logger.info(s"Sponsor SV authorizing svc party to participant $participantId")
    for {
      svcRules <- EitherT.liftF(svcStore.getSvcRules())
      svcMembersSize = svcRules.payload.members.size()
      ourParticipant <- EitherT.liftF(participantAdminConnection.getParticipantId())
      // this will wait until the PartyToParticipant state change completed
      authorizedAt <- partyHosting
        .authorizeSvcPartyToParticipant(
          globalDomain,
          participantId,
          svcMembersSize,
          ourParticipant.uid.namespace.fingerprint,
        )
      _ = logger.info(
        s"SVC party was authorized on $participantId, downloading snapshot at time $authorizedAt."
      )
      acsBytes <- EitherT.liftF(downloadSnapshotFromTime(authorizedAt))
    } yield {
      acsBytes
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def downloadSnapshotFromTime(
      authorizedAt: Instant
  )(implicit tc: TraceContext): Future[ByteString] = {
    def submitDummyTransaction(): Future[Unit] =
      svStoreWithIngestion.connection
        .submit(
          Seq(svParty),
          Seq.empty,
          // The transaction here is arbitrary with the restriction that it should not have the SVC as a stakeholder.
          // FeaturedAppRight just happens to be one of the simplest templates we have.
          new FeaturedAppRight(svParty.toProtoPrimitive, svParty.toProtoPrimitive).createAnd
            .exerciseArchive(),
        )
        .withDomainId(globalDomain)
        .noDedup
        .yieldUnit()
    // Acquiring the ACS snapshot is tricky due to two issues:
    // 1. The snapshot can only be acquired at a "clean" timestamp which means there are no outstanding ACS commitments.
    //    To ensure that the timestamp will eventually be clean we need to submit a transaction visible to the participant (submitDummyTransaction) and
    //    retry the download afterwards. Note that due to the second issue, this transaction must not change contracts with SVC as the stakeholder.
    // 2. Concurrent ACS pruning in Canton can prune the data for that timestamp. In that case, we need to fetch the data at a later timestamp.
    //    Of course this is only safe if there have not been any relevant changes in between. We ensure this using the SvcRules lock.
    //    In that case, we again need to submit a dummy transaction to ensure that the updated timestamp is clean.
    for {
      _ <- submitDummyTransaction()
      snapshot <- {
        var acsOffset = authorizedAt
        retryProvider.retryForAutomation(
          show"Download ACS snapshot for SVC at $acsOffset",
          participantAdminConnection
            .downloadAcsSnapshot(
              Set(svcParty),
              filterDomainId = Some(globalDomain),
              timestamp = Some(acsOffset),
            )
            .recoverWith { case ex: StatusRuntimeException =>
              val errorDetails = ErrorDetails.from(ex: StatusRuntimeException)
              for {
                _ <- errorDetails.traverse_ {
                  case ErrorDetails.ErrorInfoDetail("UNAVAILABLE_ACS_SNAPSHOT", metadata) =>
                    metadata.get("prunedTimestamp") match {
                      case None =>
                        logger.warn(
                          s"UNAVAILABLE_ACS_SNAPSHOT has no prunedTimestamp field: $metadata"
                        )
                        Future.unit
                      case Some(timestamp) =>
                        LfTimestamp.fromString(timestamp) match {
                          case Left(err) =>
                            logger.warn(
                              s"Failed to convert pruned timestamp from error details into an LfTimestamp: $err"
                            )
                            Future.unit
                          case Right(timestamp) =>
                            // The pruned timestamp is the latest timestamp that has been pruned
                            // so we need to increase it by one
                            acsOffset = timestamp.addMicros(1).toInstant
                            submitDummyTransaction()
                        }
                    }
                  case _ => Future.unit
                }
              } yield throw ex
            },
          logger,
        )
      }
    } yield snapshot

  }

}
