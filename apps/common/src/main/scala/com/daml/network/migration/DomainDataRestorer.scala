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

  /** We assume the domain was not register prior to trying to restore the data.
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
            importAcs(acsSnapshot)
          case Some(config) if config.manualConnect =>
            importAcs(acsSnapshot)
          case None =>
            val domainConnectionConfig = DomainConnectionConfig(
              domainAlias,
              domainId = Some(domainId),
              sequencerConnections = sequencerConnections,
              manualConnect = true,
            )
            for {
              _ <- importDars(dars)
              _ = logger.info("Imported all the dars.")
              _ <-
                // TODO(#10052) do not connect to the domain before importing the ACS
                participantAdminConnection
                  .ensureDomainRegisteredAndConnected(
                    domainConnectionConfig,
                    RetryFor.ClientCalls,
                  )
              _ <- participantAdminConnection.disconnectDomain(domainAlias)
              _ = logger.info("Importing the ACS")
              _ <- importAcs(acsSnapshot)
              _ = logger.info("Imported the ACS")
            } yield ()
          case Some(_) =>
            logger.info("Domain is already registered and initialized")
            Future.unit
        }
      _ <- participantAdminConnection.modifyDomainConnectionConfigAndReconnect(
        domainAlias,
        c =>
          Some(
            c.copy(
              manualConnect = false
            )
          ),
      )
      _ = logger.info("domain is reconnected")
    } yield ()
  }

  private def importAcs(acs: ByteString)(implicit tc: TraceContext) = {
    participantAdminConnection.uploadAcsSnapshot(
      acs
    )
  }

  private def importDars(dars: Seq[Dar])(implicit tc: TraceContext) = {
    // TODO(#5141): allow limit parallel upload once Canton deals with concurrent uploads
    MonadUtil
      .sequentialTraverse(dars.map { dar =>
        UploadablePackage.fromByteString(dar.hash.toHexString, dar.content)
      }) { dar =>
        participantAdminConnection.uploadDarFileLocally(
          dar,
          RetryFor.WaitingOnInitDependency,
        )
      }
      .map { _ =>
        ()
      }
  }

}
