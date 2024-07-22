// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.admin.http

import com.daml.network.config.SharedSpliceAppParameters
import com.daml.network.environment.{
  MediatorAdminConnection,
  ParticipantAdminConnection,
  RetryProvider,
  SequencerAdminConnection,
}
import com.daml.network.http.v0.{definitions, scan_soft_domain_migration_poc as v0}
import com.daml.network.http.v0.scan_soft_domain_migration_poc.ScanSoftDomainMigrationPocResource
import com.daml.network.scan.config.ScanSynchronizerConfig
import com.daml.network.scan.metrics.ScanAppMetrics
import com.daml.network.scan.store.ScanStore
import com.daml.network.util.Codec
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, UniqueIdentifier}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  DomainParametersState,
  MediatorDomainState,
  SequencerDomainState,
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
      domainIdPrefix: String
  )(
      extracted: TraceContext
  ): Future[ScanSoftDomainMigrationPocResource.GetSynchronizerIdentitiesResponse] = {
    implicit val tc = extracted
    synchronizers.get(domainIdPrefix) match {
      case None =>
        Future.successful(
          ScanSoftDomainMigrationPocResource.GetSynchronizerIdentitiesResponse.NotFound(
            definitions.ErrorResponse(s"No synchronizer for $domainIdPrefix found")
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
  )(domainIdPrefix: String)(
      extracted: com.digitalasset.canton.tracing.TraceContext
  ): Future[ScanSoftDomainMigrationPocResource.GetSynchronizerBootstrappingTransactionsResponse] = {
    implicit val tc = extracted
    val domainId = DomainId(
      UniqueIdentifier.tryCreate(domainIdPrefix, store.key.dsoParty.uid.namespace)
    )
    for {
      signedTransactionsProposals <- participantAdminConnection
        .listAllTransactions(
          store = TopologyStoreId.AuthorizedStore,
          proposals = true,
          includeMappings = Set(
            TopologyMapping.Code.DomainParametersState,
            TopologyMapping.Code.SequencerDomainState,
            TopologyMapping.Code.MediatorDomainState,
          ),
        )
        .map(_.map(_.transaction))
      signedTransactionsNonProposals <- participantAdminConnection
        .listAllTransactions(
          store = TopologyStoreId.AuthorizedStore,
          proposals = false,
          includeMappings = Set(
            TopologyMapping.Code.DomainParametersState,
            TopologyMapping.Code.SequencerDomainState,
            TopologyMapping.Code.MediatorDomainState,
          ),
        )
        .map(_.map(_.transaction))
      signedTransactions = signedTransactionsProposals ++ signedTransactionsNonProposals
      domainParameters = signedTransactions
        .find {
          case SignedTopologyTransaction(
                TopologyTransaction(_, _, DomainParametersState(actualDomainId, _)),
                _,
                _,
              ) if actualDomainId == domainId =>
            true
          case _ => false
        }
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(s"No domain parameter for $domainId")
            .asRuntimeException()
        )
      sequencerDomainState = signedTransactions
        .find {
          case SignedTopologyTransaction(
                TopologyTransaction(_, _, SequencerDomainState(actualDomainId, _, _, _)),
                _,
                _,
              ) if actualDomainId == domainId =>
            true
          case _ => false
        }
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(s"No sequencer domain state for $domainId")
            .asRuntimeException()
        )
      mediatorDomainState = signedTransactions
        .find {
          case SignedTopologyTransaction(
                TopologyTransaction(_, _, MediatorDomainState(actualDomainId, _, _, _, _)),
                _,
                _,
              ) if actualDomainId == domainId =>
            true
          case _ => false
        }
        .getOrElse(
          throw Status.INTERNAL
            .withDescription(s"No mediator domain state for $domainId")
            .asRuntimeException()
        )
    } yield ScanSoftDomainMigrationPocResource.GetSynchronizerBootstrappingTransactionsResponse.OK(
      definitions.SynchronizerBootstrappingTransactions(
        Base64.getEncoder.encodeToString(domainParameters.toByteArray),
        Base64.getEncoder.encodeToString(sequencerDomainState.toByteArray),
        Base64.getEncoder.encodeToString(mediatorDomainState.toByteArray),
      )
    )
  }
}
