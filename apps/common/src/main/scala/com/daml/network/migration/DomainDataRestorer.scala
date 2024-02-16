package com.daml.network.migration

import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.topology.DomainId
import com.google.protobuf.ByteString

import scala.concurrent.{ExecutionContext, Future}

class DomainDataRestorer(
    participantAdminConnection: ParticipantAdminConnection,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  /** We assume the domain was not registered prior to trying to restore the data.
    * We will register the domain with the manualConnect and initializeFromTrustedDomain flags set to true and use that as a check if the import failed after registering the domain so we know we can retry
    */
  def connectDomainAndRestoreData(
      domainAlias: DomainAlias,
      domainId: DomainId,
      sequencerConnections: SequencerConnections,
      dars: Seq[Dar],
      acsSnapshot: ByteString,
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    logger.info("Registering and connecting to new domain")
    for {
      _ <- participantAdminConnection
        .lookupDomainConnectionConfig(
          domainAlias
        )
        .flatMap {
          case Some(config) if config.initializeFromTrustedDomain =>
            importDarsAndAcs(dars, acsSnapshot)
          case None =>
            // TODO(#10052) do not connect to the domain before importing the ACS
            participantAdminConnection
              .ensureDomainRegistered(
                DomainConnectionConfig(
                  domainAlias,
                  domainId = Some(domainId),
                  sequencerConnections = sequencerConnections,
                  initializeFromTrustedDomain = true,
                  manualConnect = true,
                ),
                RetryFor.ClientCalls,
              )
              .flatMap(_ => importDarsAndAcs(dars, acsSnapshot))
          case Some(_) =>
            logger.info("Domain is already registered and initialized")
            Future.unit
        }
      _ <- participantAdminConnection.modifyDomainConnectionConfig(
        domainAlias,
        c =>
          Some(
            c.copy(
              initializeFromTrustedDomain = false,
              manualConnect = false,
            )
          ),
      )
      _ <- participantAdminConnection.reconnectDomain(domainAlias)
      _ = logger.info("domain is reconnected")
    } yield ()
  }

  private def importDarsAndAcs(dars: Seq[Dar], acs: ByteString)(implicit tc: TraceContext) = {
    for {
      // TODO(#10052) do not connect to the domain before importing the ACS
      _ <- participantAdminConnection.disconnectFromAllDomains()
      _ = logger.info(s"Disconnected from all domains")
      // TODO(#5141): allow limit parallel upload once Canton deals with concurrent uploads
      _ <- MonadUtil.sequentialTraverse(dars.map { dar =>
        UploadablePackage.fromByteString(dar.hash.toHexString, dar.content)
      }) { dar =>
        participantAdminConnection.uploadDarFileLocally(
          dar,
          RetryFor.WaitingOnInitDependency,
        )
      }
      _ = logger.info("uploaded all dars to the participant.")

      _ <- participantAdminConnection.uploadAcsSnapshot(
        acs
      )
      _ = logger.info("Acs snapshot is restored")

    } yield ()
  }

}
