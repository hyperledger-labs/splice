package com.daml.network.setup

import cats.implicits.toFoldableOps
import com.daml.network.environment.{RetryFor, RetryProvider, TopologyAdminConnection}
import com.daml.network.identities.NodeIdentitiesDump
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, NodeIdentity}
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
      targetId: Option[NodeIdentity] = None,
      domainId: Option[DomainId] = None,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    logger.info(s"Initializing node from dump: $dump")
    val expectedId = targetId.getOrElse(dump.id)
    for {
      _ <- retryProvider.ensureThatB(
        RetryFor.WaitingOnInitDependency,
        s"node is initialized with id ${expectedId}",
        connection.isNodeInitialized(),
        initializeFromDump(dump, Some(expectedId), domainId),
        logger,
      )
      id <- connection.identity()
      result <-
        if (id == expectedId) {
          Future.unit
        } else {
          Future.failed(
            Status.INTERNAL
              .withDescription(s"Node has ID $id instead of expected ID ${expectedId}.")
              .asRuntimeException()
          )
        }
    } yield result
  }

  def initializeFromDump(
      dump: NodeIdentitiesDump,
      targetId: Option[NodeIdentity] = None,
      domainId: Option[DomainId] = None,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    val expectedId = targetId.getOrElse(dump.id)
    for {
      _ <- importKeysFromDump(dump, expectedId)
      uploadedBootstrapTxs <- connection.getIdentityBootstrapTransactions(domainId, dump.id.uid)
      missingBootstrapTxs = dump.bootstrapTxs.filter(!uploadedBootstrapTxs.contains(_))
      // things blow up later in init if we upload the same tx multiple times ¯\_(ツ)_/¯
      _ <- {
        logger.info(
          s"Uploading ${missingBootstrapTxs.size} missing bootstrap transactions from dump"
        )
        connection.addTopologyTransactions(domainId, missingBootstrapTxs)
      }
      _ <-
        if (expectedId != dump.id) {
          connection.listMyKeys().flatMap { keys =>
            NonEmpty.from(keys) match {
              case None =>
                Future.failed(
                  Status.INTERNAL
                    .withDescription(
                      "Node is bootstrapping from dump but list of keys is empty"
                    )
                    .asRuntimeException
                )
              case Some(keysNE) =>
                connection.ensureInitialOwnerToKeyMapping(
                  expectedId.member,
                  keysNE.map(_.publicKey),
                  expectedId.uid.namespace.fingerprint,
                  RetryFor.Automation,
                )
            }
          }
        } else Future.unit
      _ <- {
        logger.info(s"Triggering node initialization for node with ID $expectedId")
        connection.initId(expectedId)
      }
    } yield ()
  }

  private def importKeysFromDump(
      dump: NodeIdentitiesDump,
      expectedId: NodeIdentity,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    logger.info(s"Uploading node keys from dump for id ${dump.id}, new node id: ${expectedId}")
    // this is idempotent
    dump.keys.traverse_(key => connection.importKeyPair(key.keyPair, key.name))
  }

}
