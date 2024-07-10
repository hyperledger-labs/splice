// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.setup

import cats.implicits.{showInterpolator, toFoldableOps}
import com.daml.network.environment.{RetryFor, RetryProvider, TopologyAdminConnection}
import com.daml.network.identities.NodeIdentitiesDump
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{NodeIdentity, UniqueIdentifier}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.store.TopologyStoreId.AuthorizedStore
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
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
      _ <-
        // ^ means XOR!
        // TODO(#11594): Remove this check entirely
        if (dump.bootstrapTxs.isDefined ^ dump.authorizedStoreSnapshot.isDefined) {
          Future.unit
        } else {
          Future.failed(
            Status.INTERNAL
              .withDescription(
                "Error bootstrapping from dump: exactly one of `bootstrapTxs` and `authorizedStoreSnapshot` must be defined"
              )
              .asRuntimeException
          )
        }
      _ <- dump.bootstrapTxs.traverse_ { bootstrapTxs =>
        {
          logger.warn(
            "Bootstrapping from bootstrap transactions is deprecated. Please update your node identities dump."
          )
          importBootstrapTxs(bootstrapTxs, dump.id.uid)
        }
      }
      _ <- dump.authorizedStoreSnapshot.traverse_ { snapshot =>
        importAuthorizedStoreSnapshot(snapshot)
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
    logger.info(
      s"Uploading node keys ${dump.keys.map(_.name)} from dump for id ${dump.id}, new node id: $expectedId"
    )
    // this is idempotent
    dump.keys.traverse_(key => connection.importKeyPair(key.keyPair.toArray, key.name))
  }

  private def importBootstrapTxs(
      bootstrapTxs: Seq[GenericSignedTopologyTransactionX],
      uid: UniqueIdentifier,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = for {
    uploadedBootstrapTxs <- connection.getIdentityTransactions(uid, TopologyStoreId.AuthorizedStore)
    missingBootstrapTxs = bootstrapTxs.filter(!uploadedBootstrapTxs.contains(_))
    // things blow up later in init if we upload the same tx multiple times ¯\_(ツ)_/¯
    _ <- {
      logger.info(
        s"Uploading ${missingBootstrapTxs.size} missing bootstrap transactions"
      )
      // This will be broadcast to the domain store.
      connection.addTopologyTransactions(TopologyStoreId.AuthorizedStore, missingBootstrapTxs)
    }
  } yield ()

  private def importAuthorizedStoreSnapshot(
      authorizedStoreSnapshot: ByteString
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] =
    for {
      _ <- connection.importTopologySnapshot(authorizedStoreSnapshot, AuthorizedStore)
      _ = logger.info(s"AuthorizedStore snapshot is imported")
    } yield ()
}
