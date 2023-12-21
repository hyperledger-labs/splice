package com.daml.network.setup

import cats.implicits.toFoldableOps
import com.daml.network.environment.{RetryFor, RetryProvider, TopologyAdminConnection}
import com.daml.network.identities.NodeIdentitiesDump
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

class NodeInitializer(
    connection: TopologyAdminConnection,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  def initializeAndWait(
      dump: NodeIdentitiesDump,
      domainId: Option[DomainId] = None,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    logger.info(s"Initializing node from dump: $dump")
    for {
      _ <- retryProvider.ensureThatB(
        RetryFor.WaitingOnInitDependency,
        s"node is initialized with id ${dump.id}",
        connection.isNodeInitialized(),
        initializeFromDump(dump, domainId),
        logger,
      )
      id <- connection.identity()
      result <-
        if (id == dump.id) {
          Future.unit
        } else {
          Future.failed(
            Status.INTERNAL
              .withDescription(s"Node has ID $id instead of expected ID ${dump.id}.")
              .asRuntimeException()
          )
        }
    } yield result
  }

  def initializeFromDump(
      dump: NodeIdentitiesDump,
      domainId: Option[DomainId] = None,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = for {
    _ <- importKeysFromDump(dump)
    uploadedBootstrapTxs <- connection.getIdentityBootstrapTransactions(domainId, dump.id.uid)
    missingBootstrapTxs = dump.bootstrapTxs.filter(!uploadedBootstrapTxs.contains(_))
    // things blow up later in init if we upload the same tx multiple times ¯\_(ツ)_/¯
    _ <- {
      logger.info(s"Uploading ${missingBootstrapTxs.size} missing bootstrap transactions from dump")
      connection.addTopologyTransactions(domainId, missingBootstrapTxs)
    }
    _ <- {
      logger.info(s"Triggering participant initialization for participant ID ${dump.id}")
      connection.initId(dump.id)
    }
  } yield ()

  private def importKeysFromDump(
      dump: NodeIdentitiesDump
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    logger.info(s"Uploading node keys from dump for id ${dump.id}")
    // this is idempotent
    dump.keys.traverse_(key => connection.importKeyPair(key.keyPair, key.name))
  }

}
