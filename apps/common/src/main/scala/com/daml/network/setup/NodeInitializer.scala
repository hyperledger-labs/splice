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
import com.digitalasset.canton.topology.NodeIdentity
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
