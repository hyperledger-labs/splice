package com.daml.network.sv.onboarding.sponsor

import cats.data.{EitherT, OptionT}
import cats.implicits.catsSyntaxTuple2Semigroupal
import cats.syntax.foldable.*
import com.daml.error.utils.ErrorDetails
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.config.CNThresholds
import com.daml.network.environment.{
  ParticipantAdminConnection,
  RetryProvider,
  TopologyAdminConnection,
}
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.sv.onboarding.SvcPartyHosting
import com.daml.network.sv.onboarding.SvcPartyHosting.{
  RequiredProposalsNotFound,
  SvcPartyMigrationFailure,
}
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.SvcRulesLock
import com.digitalasset.canton.LfTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.transaction.{PartyToParticipantX, UnionspaceDefinitionX}
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
    svcRulesLock: SvcRulesLock,
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
    // As a work around to #3933, prevent participant from crashing when authorization transaction is being processed
    // TODO(#3933): we can remove this when canton team has completed a proper fix to #3933
    svcRulesLock
      .withLock(s"authorizing SVC party to participant $participantId")(
        for {
          svcRules <- EitherT.liftF(svcStore.getSvcRules())
          svcMembersSize = svcRules.payload.members.size()
          acsBytes <- validateProposalsForNewMember(participantId, svcMembersSize)
            .semiflatMap { _ =>
              for {
                ourParticipant <- participantAdminConnection.getParticipantId()
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
                acsBytes <- downloadSnapshotFromTime(authorizedAt)
                _ = logger.info(
                  s"Adding candidate SV with participant $participantId to unionspace"
                )
                _ <- participantAdminConnection.ensureUnionspaceDefinition(
                  globalDomain,
                  svcParty.uid.namespace,
                  participantId.uid.namespace,
                  ourParticipant.uid.namespace.fingerprint,
                )
              } yield acsBytes
            }
        } yield {
          acsBytes
        }
      )
  }

  private def validateProposalsForNewMember(
      participantId: ParticipantId,
      svcMembersSize: Int,
  )(implicit tc: TraceContext): EitherT[
    Future,
    SvcPartyMigrationFailure,
    (
        TopologyAdminConnection.TopologyResult[PartyToParticipantX],
        TopologyAdminConnection.TopologyResult[UnionspaceDefinitionX],
    ),
  ] = {
    val partyToParticipantAcceptedState =
      participantAdminConnection.getPartyToParticipant(globalDomain, svcParty)
    lazy val acceptedState = (
      partyToParticipantAcceptedState,
      participantAdminConnection
        .getUnionspaceDefinition(globalDomain, svcParty.uid.namespace),
    ).tupled
    (
      OptionT(
        participantAdminConnection
          .listPartyToParticipant(
            globalDomain.filterString,
            filterParty = svcParty.filterString,
            proposals = true,
          )
          .flatMap { proposals =>
            partyToParticipantAcceptedState
              .map(acceptedState =>
                CNThresholds.getPartyToParticipantThreshold(
                  svcMembersSize,
                  acceptedState.mapping.participantIds.size,
                )
              )
              .map { expectedThreshold =>
                proposals.find(proposal =>
                  proposal.mapping.participantIds
                    .contains(participantId) && proposal.mapping.threshold == expectedThreshold
                )
              }
          }
      ),
      participantAdminConnection.findUnionspaceDefinitionProposal(
        globalDomain,
        svcParty.uid.namespace,
        participantId.uid.namespace,
      ),
    ).tupled
      .orElse(OptionT.liftF(acceptedState).filter {
        case (partyToParticipant, unionspaceDefinition) =>
          partyToParticipant.mapping.participantIds.contains(
            participantId
          ) && unionspaceDefinition.mapping.owners.contains(participantId.uid.namespace)
      })
      .toRightF {
        acceptedState.map { case (partyToParticipant, unionspaceDefinition) =>
          RequiredProposalsNotFound(
            partyToParticipant.base.serial,
            unionspaceDefinition.base.serial,
          )
        }
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
