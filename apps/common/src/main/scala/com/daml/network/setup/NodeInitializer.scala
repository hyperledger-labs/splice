// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.setup

import cats.implicits.{showInterpolator, toFoldableOps}
import com.daml.network.environment.{
  RetryFor,
  RetryProvider,
  StatusAdminConnection,
  TopologyAdminConnection,
}
import com.daml.network.identities.NodeIdentitiesDump
import com.daml.network.util.PrettyInstances.prettyString
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.health.admin.data.{NodeStatus, WaitingForId}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{Member, Namespace, NodeIdentity, UniqueIdentifier}
import com.digitalasset.canton.topology.store.TopologyStoreId.AuthorizedStore
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

class NodeInitializer(
    connection: TopologyAdminConnection & StatusAdminConnection,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  /** Returns a future that completes after the node returns a "Success" response to a status request,
    * indicating that the node is initialized.
    * Note that "initialized" has different meanings for different nodes.
    * For example, a participant node is "initialized" when it has an identity, but a sequencer node also needs
    * to be initialized with the bootstrapping state and connect to the domain before it considers itself "initialized".
    */
  def waitForNodeInitialized()(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] =
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "node_initialized",
      s"${connection.serviceName} is initialized",
      connection.isNodeInitialized().map {
        case true => ()
        case false =>
          throw Status.UNAVAILABLE
            .withDescription(
              show"${connection.serviceName} is not initialized"
            )
            .asRuntimeException()
      },
      logger,
    )

  // Note: this method is not idempotent or crash fault tolerant. We accept that it may brick a node if initialization
  // fails, since we can easily recover by resetting the node.
  def initializeWithNewIdentity(
      identifierName: String,
      nodeIdentity: UniqueIdentifier => Member & NodeIdentity,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    for {
      // All nodes need a signing key
      signingKey <- connection.generateKeyPair("signing")

      // Only participants need an encryption key, but for simplicity every node gets one
      encryptionKey <- connection.generateEncryptionKeyPair("encryption")

      // Construct the fresh node identity from the signing key fingerprint and the name hint.
      // This mirrors the implementation in the Canton auto-init stage, see [[CantonNodeBootstrapX.SetupNodeId.autoCompleteStage()]].
      namespace = Namespace(signingKey.id)
      uid = UniqueIdentifier.tryCreate(identifierName, signingKey.id.toProtoPrimitive)
      nodeId = nodeIdentity(uid)

      // Setting node identity
      _ = logger.info(s"Triggering node initialization for node with ID $nodeId")
      _ <- connection.initId(nodeId)

      // Adding root certificate
      _ <- connection.ensureNamespaceDelegation(
        namespace = namespace,
        target = signingKey,
        isRootDelegation = true,
        signedBy = namespace.fingerprint,
        retryFor = RetryFor.Automation,
      )

      // Adding owner-to-key mappings
      _ <- connection.ensureInitialOwnerToKeyMapping(
        member = nodeId,
        keys = NonEmpty(Seq, signingKey, encryptionKey),
        signedBy = namespace.fingerprint,
        retryFor = RetryFor.Automation,
      )
    } yield {
      logger.info(s"Finished node initialization for node with ID $nodeId")
    }
  }

  def initializeWithNewIdentityIfNeeded(
      idenfitierName: String,
      nodeIdentity: UniqueIdentifier => Member & NodeIdentity,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    logger.info(s"Making sure canton node has an identity")
    for {
      // If the node was started concurrently with the app, it might not immediately be responding, so we're
      // retrying the getId() call.
      // Note that Canton nodes enable their endpoints one at a time, and return NOT_IMPLEMENTED while an endpoint
      // is not yet enabled. E.g., even if a node returned something to a getStatus() request, it might still fail
      // a subsequent getId() request with NOT_IMPLEMENTED.
      nodeId <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "node_id",
        s"${connection.serviceName} answers the getId request",
        connection.getIdOption(),
        logger,
      )
      _ <- nodeId.uniqueIdentifier match {
        case Some(id) =>
          if (id.identifier.unwrap == idenfitierName) {
            logger.info(
              s"Node has identity $id, matching expected identifier $idenfitierName."
            )
            Future.unit
          } else {
            logger.error(
              s"Node has identity $id, but identifier $idenfitierName was expected."
            )
            Future.failed(
              Status.INTERNAL
                .withDescription(
                  s"Node has identity $id, but identifier $idenfitierName was expected."
                )
                .asRuntimeException()
            )
          }
        case None =>
          logger.info(s"Node has no identity, generating a new one")
          initializeWithNewIdentity(idenfitierName, nodeIdentity)
      }
    } yield ()
  }

  def initializeFromDumpAndWait(
      dump: NodeIdentitiesDump,
      targetId: Option[NodeIdentity] = None,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    logger.info(show"Initializing node from dump: $dump")
    val expectedId = targetId.getOrElse(dump.id)
    for {
      _ <- retryProvider.ensureThatB(
        RetryFor.WaitingOnInitDependency,
        "node_init",
        s"node is initialized with id $expectedId",
        connection.isNodeInitialized(),
        initializeFromDump(dump, Some(expectedId)),
        logger,
      )
      id <- connection.identity()
      result <-
        if (id == expectedId) {
          Future.unit
        } else {
          Future.failed(
            Status.INTERNAL
              .withDescription(s"Node has ID $id instead of expected ID $expectedId.")
              .asRuntimeException()
          )
        }
    } yield result
  }

  def initializeFromDump(
      dump: NodeIdentitiesDump,
      targetId: Option[NodeIdentity] = None,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    val expectedId = targetId.getOrElse(dump.id)
    for {
      _ <- importKeysFromDump(dump, expectedId)
      // the id must be initialized before we can import the snapshot
      _ <- retryProvider.ensureThat(
        RetryFor.WaitingOnInitDependency,
        "node_init",
        s"node is initialized with id $expectedId",
        connection.getStatus.map[Either[String, Unit]] {
          case NodeStatus.Failure(msg) => Left(s"Node is in failure state: $msg")
          // the first step in the canton init process
          case NodeStatus.NotInitialized(_, Some(WaitingForId)) => Left("Node is waiting for an ID")
          case NodeStatus.NotInitialized(_, _) => Right(())
          case NodeStatus.Success(_) => Right(())
        },
        (_: String) => connection.initId(expectedId).map(_ => ()),
        logger,
      )(implicitly, implicitly, prettyString, implicitly, implicitly)
      _ <- importAuthorizedStoreSnapshot(dump.authorizedStoreSnapshot)
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
    } yield ()
  }

  private def importKeysFromDump(
      dump: NodeIdentitiesDump,
      expectedId: NodeIdentity,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    logger.info(
      s"Uploading node keys ${dump.keys.map(_.name)} from dump for id ${dump.id}, new node id: $expectedId"
    )
    // this is idempotent
    dump.keys.traverse_(key => connection.importKeyPair(key.keyPair.toArray, key.name))
  }

  private def importAuthorizedStoreSnapshot(
      authorizedStoreSnapshot: ByteString
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] =
    for {
      _ <- connection.importTopologySnapshot(authorizedStoreSnapshot, AuthorizedStore)
      _ = logger.info(s"AuthorizedStore snapshot is imported")
    } yield ()
}
