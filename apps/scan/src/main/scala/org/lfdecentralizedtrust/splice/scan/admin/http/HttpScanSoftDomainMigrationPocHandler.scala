// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  ParticipantAdminConnection,
  RetryProvider,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.http.v0.{definitions, scan_soft_domain_migration_poc as v0}
import org.lfdecentralizedtrust.splice.http.v0.scan_soft_domain_migration_poc.ScanSoftDomainMigrationPocResource
import org.lfdecentralizedtrust.splice.scan.config.ScanSynchronizerConfig
import org.lfdecentralizedtrust.splice.scan.metrics.ScanAppMetrics
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.util.Codec
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  SynchronizerParametersState,
  MediatorSynchronizerState,
  SequencerSynchronizerState,
  SignedTopologyTransaction,
  TopologyMapping,
  TopologyTransaction,
}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}
import java.util.Base64

class HttpScanSoftDomainMigrationPocHandler(
    participantAdminConnection: ParticipantAdminConnection,
    store: ScanStore,
    protected val loggerFactory: NamedLoggerFactory,
    amuletAppParameters: SharedSpliceAppParameters,
    metrics: ScanAppMetrics,
    synchronizers: Map[String, ScanSynchronizerConfig],
    retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends v0.ScanSoftDomainMigrationPocHandler[TraceContext]
    with Spanning
    with NamedLogging {

  private def withSequencerAdminConnection[T](
      config: ClientConfig
  )(f: SequencerAdminConnection => Future[T]): Future[T] = {
    val connection = new SequencerAdminConnection(
      config,
      amuletAppParameters.loggingConfig.api,
      loggerFactory,
      metrics.grpcClientMetrics,
      retryProvider,
    )
    f(connection).andThen { _ => connection.close() }
  }

  private def withMediatorAdminConnection[T](
      config: ClientConfig
  )(f: MediatorAdminConnection => Future[T]): Future[T] = {
    val connection = new MediatorAdminConnection(
      config,
      amuletAppParameters.loggingConfig.api,
      loggerFactory,
      metrics.grpcClientMetrics,
      retryProvider,
    )
    f(connection).andThen { _ => connection.close() }
  }

  override def getSynchronizerIdentities(
      respond: ScanSoftDomainMigrationPocResource.GetSynchronizerIdentitiesResponse.type
  )(
      synchronizerIdPrefix: String
  )(
      extracted: TraceContext
  ): Future[ScanSoftDomainMigrationPocResource.GetSynchronizerIdentitiesResponse] = {
    implicit val tc = extracted
    synchronizers.get(synchronizerIdPrefix) match {
      case None =>
        Future.successful(
          ScanSoftDomainMigrationPocResource.GetSynchronizerIdentitiesResponse.NotFound(
            definitions.ErrorResponse(s"No synchronizer for $synchronizerIdPrefix found")
          )
        )
      case Some(synchronizer) =>
        for {
          (sequencerId, sequencerIdentityTransactions) <- withSequencerAdminConnection(
            synchronizer.sequencer
          ) { c =>
            for {
              id <- c.getSequencerId
              txs <- c.getIdentityTransactions(id.uid, TopologyStoreId.AuthorizedStore)
            } yield (id, txs)
          }
          (mediatorId, mediatorIdentityTransactions) <- withMediatorAdminConnection(
            synchronizer.mediator
          ) { c =>
            for {
              id <- c.getMediatorId
              txs <- c.getIdentityTransactions(id.uid, TopologyStoreId.AuthorizedStore)
            } yield (id, txs)
          }
        } yield ScanSoftDomainMigrationPocResource.GetSynchronizerIdentitiesResponse(
          definitions.SynchronizerIdentities(
            Codec.encode(sequencerId),
            sequencerIdentityTransactions
              .map(tx => Base64.getEncoder.encodeToString(tx.toByteArray))
              .toVector,
            Codec.encode(mediatorId),
            mediatorIdentityTransactions
              .map(tx => Base64.getEncoder.encodeToString(tx.toByteArray))
              .toVector,
          )
        )
    }
  }

  def getSynchronizerBootstrappingTransactions(
      respond: ScanSoftDomainMigrationPocResource.GetSynchronizerBootstrappingTransactionsResponse.type
  )(synchronizerIdPrefix: String)(
      extracted: com.digitalasset.canton.tracing.TraceContext
  ): Future[ScanSoftDomainMigrationPocResource.GetSynchronizerBootstrappingTransactionsResponse] = {
    implicit val tc = extracted
    val synchronizerId = SynchronizerId(
      UniqueIdentifier.tryCreate(synchronizerIdPrefix, store.key.dsoParty.uid.namespace)
    )
    for {
      signedTransactionsProposals <- participantAdminConnection
        .listAllTransactions(
          store = TopologyStoreId.AuthorizedStore,
          proposals = true,
          includeMappings = Set(
            TopologyMapping.Code.SynchronizerParametersState,
            TopologyMapping.Code.SequencerSynchronizerState,
            TopologyMapping.Code.MediatorSynchronizerState,
          ),
        )
        .map(_.map(_.transaction))
      signedTransactionsNonProposals <- participantAdminConnection
        .listAllTransactions(
          store = TopologyStoreId.AuthorizedStore,
          proposals = false,
          includeMappings = Set(
            TopologyMapping.Code.SynchronizerParametersState,
            TopologyMapping.Code.SequencerSynchronizerState,
            TopologyMapping.Code.MediatorSynchronizerState,
          ),
        )
        .map(_.map(_.transaction))
      signedTransactions = signedTransactionsProposals ++ signedTransactionsNonProposals
      domainParameters = signedTransactions
        .find {
          case SignedTopologyTransaction(
                TopologyTransaction(_, _, SynchronizerParametersState(actualSynchronizerId, _)),
                _,
                _,
              ) if actualSynchronizerId == synchronizerId =>
            true
          case _ => false
        }
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(s"No domain parameter for $synchronizerId")
            .asRuntimeException()
        )
      sequencerSynchronizerState = signedTransactions
        .find {
          case SignedTopologyTransaction(
                TopologyTransaction(
                  _,
                  _,
                  SequencerSynchronizerState(actualSynchronizerId, _, _, _),
                ),
                _,
                _,
              ) if actualSynchronizerId == synchronizerId =>
            true
          case _ => false
        }
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(s"No sequencer domain state for $synchronizerId")
            .asRuntimeException()
        )
      mediatorDomainState = signedTransactions
        .find {
          case SignedTopologyTransaction(
                TopologyTransaction(
                  _,
                  _,
                  MediatorSynchronizerState(actualSynchronizerId, _, _, _, _),
                ),
                _,
                _,
              ) if actualSynchronizerId == synchronizerId =>
            true
          case _ => false
        }
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(s"No mediator domain state for $synchronizerId")
            .asRuntimeException()
        )
    } yield ScanSoftDomainMigrationPocResource.GetSynchronizerBootstrappingTransactionsResponse.OK(
      definitions.SynchronizerBootstrappingTransactions(
        Base64.getEncoder.encodeToString(domainParameters.toByteArray),
        Base64.getEncoder.encodeToString(sequencerSynchronizerState.toByteArray),
        Base64.getEncoder.encodeToString(mediatorDomainState.toByteArray),
      )
    )
  }
}
