// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.implicits.catsSyntaxApplicativeError
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.caching.ScaffeineCache
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.github.blemale.scaffeine.Scaffeine
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

class SynchronizerNodeService[T <: SynchronizerNode](
    val nodes: SynchronizerNode.LocalSynchronizerNodes[T],
    participantAdminConnection: ParticipantAdminConnection,
    globalSynchronizerAlias: SynchronizerAlias,
    cacheExpiration: NonNegativeFiniteDuration,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private val successorActiveRef = new java.util.concurrent.atomic.AtomicReference(false)

  private val successorActiveCache =
    ScaffeineCache.buildTracedAsync[Future, Unit, Boolean](
      Scaffeine().expireAfterWrite(cacheExpiration.asFiniteApproximation),
      implicit tc => _ => successorActiveUncached(),
    )(logger, "successorActive")

  private def successorActive()(implicit tc: TraceContext): Future[Boolean] =
    if (successorActiveRef.get()) {
      Future.successful(true)
    } else {
      successorActiveCache.get(()).map { active =>
        if (active) {
          logger.info("Switching connection to successor synchronizer")
          successorActiveRef.set(active)
        }
        active
      }
    }

  private def successorActiveUncached()(implicit tc: TraceContext): Future[Boolean] =
    nodes.successor match {
      case None => Future.successful(false)
      case Some(successor) =>
        for {
          synchronizers <- participantAdminConnection.listRegisteredSynchronizers()
          succesorInitialized <- successor.sequencerAdminConnection
            .isNodeInitialized()
            .attemptT
            .getOrElse(false)
          global = synchronizers
            .find(
              _._1.synchronizerAlias == globalSynchronizerAlias
            )
            .flatMap(_._2.toOption)
            .getOrElse(
              throw Status.NOT_FOUND
                .withDescription(
                  s"No registered synchronizer with alias $globalSynchronizerAlias that has a physical synchronizer id"
                )
                .asRuntimeException
            )
          successorPSId <-
            if (succesorInitialized)
              successor.sequencerAdminConnection.getPhysicalSynchronizerId().map(Some(_))
            else Future.successful(None)
        } yield successorPSId.map(_.serial).contains(global.serial)
    }

  def activeSynchronizerNode()(implicit tc: TraceContext): Future[T] =
    nodes.successor match {
      case None => Future.successful(nodes.current)
      case Some(successor) =>
        successorActive().map {
          if (_) {
            successor
          } else {
            nodes.current
          }
        }
    }

  def sequencerAdminConnection()(implicit tc: TraceContext): Future[SequencerAdminConnection] =
    activeSynchronizerNode().map(_.sequencerAdminConnection)
}
