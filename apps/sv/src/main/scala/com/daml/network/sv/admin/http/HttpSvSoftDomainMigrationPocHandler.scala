// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.admin.http

import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.config.{Thresholds, NetworkAppClientConfig, SharedSpliceAppParameters}
import com.daml.network.environment.{
  MediatorAdminConnection,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SequencerAdminConnection,
}
import com.daml.network.environment.TopologyAdminConnection.TopologyTransactionType
import com.daml.network.http.HttpClient
import com.daml.network.http.v0.{sv_soft_domain_migration_poc as v0}
import com.daml.network.http.v0.sv_soft_domain_migration_poc.SvSoftDomainMigrationPocResource
import com.daml.network.store.AppStoreWithIngestion
import com.daml.network.scan.admin.api.client.SingleScanConnection
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.sv.LocalSynchronizerNode
import com.daml.network.sv.config.SvSynchronizerNodeConfig
import com.daml.network.sv.metrics.SvAppMetrics
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.util.TemplateJsonDecoder
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.{ClientConfig, CommunityCryptoConfig, CommunityCryptoProvider}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, Identifier, UniqueIdentifier}
import com.digitalasset.canton.topology.processing.{EffectiveTime, SequencedTime}
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransactionX,
  StoredTopologyTransactionsX,
  TimeQuery,
  TopologyStoreId,
}
import StoredTopologyTransactionX.GenericStoredTopologyTransactionX
import com.digitalasset.canton.topology.transaction.{
  DomainParametersStateX,
  MediatorDomainStateX,
  SequencerDomainStateX,
  TopologyMappingX,
}
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer

import cats.syntax.either.*
import cats.syntax.traverse.*
import io.grpc.Status
import org.apache.pekko.stream.Materializer
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

// TODO(#13301) Validate that topology reads return the right amount of data
class HttpSvSoftDomainMigrationPocHandler(
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    localSynchronizerNode: Option[LocalSynchronizerNode],
    synchronizerNodes: Map[String, SvSynchronizerNodeConfig],
    participantAdminConnection: ParticipantAdminConnection,
    clock: Clock,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    val amuletAppParameters: SharedSpliceAppParameters,
    metrics: SvAppMetrics,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends v0.SvSoftDomainMigrationPocHandler[TracedUser]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName
  private val dsoStore = dsoStoreWithIngestion.store

  private def getScanUrls()(implicit tc: TraceContext): Future[Seq[String]] = {
    for {
      // TODO(#13301) We should use the internal URL for the SV’s own scan to avoid a loopback requirement
      dsoRulesWithMemberNodeStates <- dsoStore.getDsoRulesWithMemberNodeStates()
    } yield dsoRulesWithMemberNodeStates.svNodeStates.values
      .flatMap(
        _.payload.state.synchronizerNodes.asScala.values
          .flatMap(_.scan.toScala.toList.map(_.publicUrl))
      )
      .toList
      // sorted to make it deterministic
      .sorted
  }

  private def withScanConnection[T](
      url: String
  )(f: SingleScanConnection => Future[T])(implicit tc: TraceContext): Future[T] =
    SingleScanConnection.withSingleScanConnection(
      ScanAppClientConfig(
        NetworkAppClientConfig(
          url
        )
      ),
      amuletAppParameters.upgradesConfig,
      clock,
      retryProvider,
      loggerFactory,
    )(f)

  /** Read the existing decentralized namespace definition including all prerequisite key txs from the existing domain store.
    */
  private def getDecentralizedNamespaceDefinitionTransactions()(implicit
      tc: TraceContext
  ): Future[Seq[GenericSignedTopologyTransactionX]] = for {
    decentralizedSynchronizerId <- dsoStore.getAmuletRulesDomain()(tc)
    namespaceDefinitions <- participantAdminConnection.listDecentralizedNamespaceDefinition(
      decentralizedSynchronizerId,
      decentralizedSynchronizerId.uid.namespace,
      timeQuery = TimeQuery.Range(None, None),
    )
    identityTransactions <- namespaceDefinitions
      .flatMap(_.mapping.owners)
      .toSet
      .toList
      .traverse { namespace =>
        participantAdminConnection.listAllTransactions(
          TopologyStoreId.DomainStore(decentralizedSynchronizerId),
          TimeQuery.Range(None, None),
          includeMappings = Set(
            TopologyMappingX.Code.OwnerToKeyMappingX,
            TopologyMappingX.Code.NamespaceDelegationX,
          ),
          filterNamespace = Some(namespace),
        )
      }
      .map(_.flatten)
    decentralizedNamespaceDefinition <- participantAdminConnection.listAllTransactions(
      TopologyStoreId.DomainStore(decentralizedSynchronizerId),
      TimeQuery.Range(None, None),
      includeMappings = Set(TopologyMappingX.Code.DecentralizedNamespaceDefinitionX),
    )
  } yield (identityTransactions ++ decentralizedNamespaceDefinition).map(_.transaction)

  override def signSynchronizerBootstrappingState(
      respond: SvSoftDomainMigrationPocResource.SignSynchronizerBootstrappingStateResponse.type
  )(
      domainIdPrefix: String
  )(
      extracted: TracedUser
  ): Future[SvSoftDomainMigrationPocResource.SignSynchronizerBootstrappingStateResponse] = {
    implicit val TracedUser(_, traceContext) = extracted
    withSpan(s"$workflowId.$signSynchronizerBootstrappingState") { _ => _ =>
      for {
        scanUrls <- getScanUrls()
        synchronizerIdentities <- scanUrls.traverse { url =>
          withScanConnection(url)(_.getSynchronizerIdentities(domainIdPrefix))
        }
        domainId = DomainId(
          UniqueIdentifier(
            Identifier.tryCreate(domainIdPrefix),
            dsoStore.key.dsoParty.uid.namespace,
          )
        )
        sequencers = synchronizerIdentities.map(_.sequencerId)
        mediators = synchronizerIdentities.map(_.mediatorId)
        existingSynchronizer = localSynchronizerNode.getOrElse(
          throw Status.INTERNAL.withDescription("Missing synchronizer").asRuntimeException()
        )
        decentralizedSynchronizerId <- dsoStore.getAmuletRulesDomain()(traceContext)
        // for now we just copy the parameters from the existing domain.
        parameters <- existingSynchronizer.sequencerAdminConnection.getDomainParametersState(
          decentralizedSynchronizerId
        )
        domainParameters = DomainParametersStateX(
          domainId,
          parameters.mapping.parameters,
        )
        sequencerDomainState = SequencerDomainStateX
          .create(
            domainId,
            Thresholds.sequencerConnectionsSizeThreshold(sequencers.size),
            sequencers,
            Seq.empty,
          )
          .valueOr(err =>
            throw Status.INTERNAL
              .withDescription(s"Failed to construct SequencerDomainState: $err")
              .asRuntimeException
          )
        mediatorDomainState = MediatorDomainStateX
          .create(
            domainId,
            NonNegativeInt.zero,
            Thresholds.mediatorDomainStateThreshold(mediators.size),
            mediators,
            Seq.empty,
          )
          .valueOr(err =>
            throw Status.INTERNAL
              .withDescription(s"Failed to construct MediatorDomainState: $err")
              .asRuntimeException
          )
        participantId <- participantAdminConnection.getParticipantId()
        decentralizedNamespaceTxs <- getDecentralizedNamespaceDefinitionTransactions()
        _ <- participantAdminConnection.addTopologyTransactions(
          TopologyStoreId.AuthorizedStore,
          decentralizedNamespaceTxs,
        )
        signedBy = participantId.uid.namespace.fingerprint
        _ <- retryProvider.ensureThatB(
          RetryFor.ClientCalls,
          "domain_parameters",
          "domain parameters are signed",
          for {
            proposalsExist <- participantAdminConnection
              .listDomainParametersState(
                TopologyStoreId.AuthorizedStore,
                domainId,
                TopologyTransactionType.AllProposals,
                TimeQuery.HeadState,
              )
              .map(_.nonEmpty)
            authorizedExist <-
              participantAdminConnection
                .listDomainParametersState(
                  TopologyStoreId.AuthorizedStore,
                  domainId,
                  TopologyTransactionType.AuthorizedState,
                  TimeQuery.HeadState,
                )
                .map(_.nonEmpty)
          } yield proposalsExist || authorizedExist,
          participantAdminConnection
            .proposeMapping(
              TopologyStoreId.AuthorizedStore,
              domainParameters,
              signedBy = signedBy,
              serial = PositiveInt.one,
              isProposal = true,
            )
            .map(_ => ()),
          logger,
        )
        // add sequencer keys, note that in 3.0 not adding these does not fail but in 3.1 it will
        _ <- participantAdminConnection.addTopologyTransactions(
          TopologyStoreId.AuthorizedStore,
          synchronizerIdentities.flatMap(_.sequencerIdentityTransactions),
        )
        _ <- retryProvider.ensureThatB(
          RetryFor.ClientCalls,
          "sequencer_domain_state",
          "sequencer domain state is signed",
          for {
            proposalsExist <- participantAdminConnection
              .listSequencerDomainState(
                TopologyStoreId.AuthorizedStore,
                domainId,
                TimeQuery.HeadState,
                true,
              )
              .map(_.nonEmpty)
            authorizedExist <-
              participantAdminConnection
                .listSequencerDomainState(
                  TopologyStoreId.AuthorizedStore,
                  domainId,
                  TimeQuery.HeadState,
                  false,
                )
                .map(_.nonEmpty)
          } yield proposalsExist || authorizedExist,
          participantAdminConnection
            .proposeMapping(
              TopologyStoreId.AuthorizedStore,
              sequencerDomainState,
              signedBy = signedBy,
              serial = PositiveInt.one,
              isProposal = true,
            )
            .map(_ => ()),
          logger,
        )
        // add mediator keys, note that in 3.0 not adding these does not fail but in 3.1 it will
        _ <- participantAdminConnection.addTopologyTransactions(
          TopologyStoreId.AuthorizedStore,
          synchronizerIdentities.flatMap(_.mediatorIdentityTransactions),
        )
        _ <- retryProvider.ensureThatB(
          RetryFor.ClientCalls,
          "mediator_domain_state",
          "mediator domain state is signed",
          for {
            proposalsExist <- participantAdminConnection
              .listMediatorDomainState(
                TopologyStoreId.AuthorizedStore,
                domainId,
                true,
              )
              .map(_.nonEmpty)
            authorizedExist <-
              participantAdminConnection
                .listMediatorDomainState(
                  TopologyStoreId.AuthorizedStore,
                  domainId,
                  false,
                )
                .map(_.nonEmpty)
          } yield proposalsExist || authorizedExist,
          participantAdminConnection
            .proposeMapping(
              TopologyStoreId.AuthorizedStore,
              mediatorDomainState,
              signedBy = signedBy,
              serial = PositiveInt.one,
              isProposal = true,
            )
            .map(_ => ()),
          logger,
        )
      } yield SvSoftDomainMigrationPocResource.SignSynchronizerBootstrappingStateResponse.OK
    }
  }

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

  // Takes a list of (ordered) signed topology transactions and turns them into
  // StoredTopologyTransactions ensuring that only the latest serial has validUntil = None
  private def toStoredTopologyBootstrapTransactions(
      ts: Seq[GenericSignedTopologyTransactionX]
  ): Seq[GenericStoredTopologyTransactionX] =
    ts.foldRight(
      (Set.empty[TopologyMappingX.MappingHash], Seq.empty[GenericStoredTopologyTransactionX])
    ) { case (tx, (newerMappings, acc)) =>
      (
        newerMappings + tx.transaction.mapping.uniqueKey,
        StoredTopologyTransactionX(
          SequencedTime(CantonTimestamp.MinValue.immediateSuccessor),
          EffectiveTime(CantonTimestamp.MinValue.immediateSuccessor),
          Option.when(newerMappings.contains(tx.transaction.mapping.uniqueKey))(
            EffectiveTime(CantonTimestamp.MinValue.immediateSuccessor)
          ),
          tx.copy(isProposal = false),
        ) +: acc,
      )
    }._2

// TODO(#13301) Add safeguards that data written to authorized store is sensible
  override def initializeSynchronizer(
      respond: SvSoftDomainMigrationPocResource.InitializeSynchronizerResponse.type
  )(
      domainIdPrefix: String
  )(
      extracted: TracedUser
  ): Future[SvSoftDomainMigrationPocResource.InitializeSynchronizerResponse] = {
    implicit val TracedUser(_, traceContext) = extracted
    val domainId = DomainId(
      UniqueIdentifier(
        Identifier.tryCreate(domainIdPrefix),
        dsoStore.key.dsoParty.uid.namespace,
      )
    )
    for {
      scanUrls <- getScanUrls()
      decentralizedNamespaceTxs <- getDecentralizedNamespaceDefinitionTransactions()
      synchronizerIdentities <- scanUrls
        .traverse { url =>
          logger.info(s"Querying synchronizer identities from $url")
          withScanConnection(url)(_.getSynchronizerIdentities(domainIdPrefix))
        }
      bootstrappingStates <- scanUrls
        .traverse { url =>
          logger.info(s"Querying bootstrapping transactions from $url")
          withScanConnection(url)(_.getSynchronizerBootstrappingTransactions(domainIdPrefix))
        }
        .map(
          NonEmpty
            .from(_)
            .getOrElse(
              throw Status.INTERNAL.withDescription("Empty list of scan urls").asRuntimeException()
            )
        )
      domainParameters = bootstrappingStates
        .map(_.domainParameters)
        .reduceLeft((a, b) => a.addSignatures(b.signatures.toSeq))
      sequencerDomainState = bootstrappingStates
        .map(_.sequencerDomainState)
        .reduceLeft((a, b) => a.addSignatures(b.signatures.toSeq))
      mediatorDomainState = bootstrappingStates
        .map(_.mediatorDomainState)
        .reduceLeft((a, b) => a.addSignatures(b.signatures.toSeq))
      node = synchronizerNodes(domainIdPrefix)
      bootstrapTransactions = toStoredTopologyBootstrapTransactions(
        decentralizedNamespaceTxs ++
          synchronizerIdentities.flatMap(_.sequencerIdentityTransactions) ++
          synchronizerIdentities.flatMap(_.mediatorIdentityTransactions) ++
          Seq(
            domainParameters,
            sequencerDomainState,
            mediatorDomainState,
          )
      )
      staticDomainParameters = node.parameters
        .toStaticDomainParameters(
          CommunityCryptoConfig(provider = CommunityCryptoProvider.Tink)
        )
        .valueOr(err =>
          throw new IllegalArgumentException(s"Invalid domain parameters config: $err")
        )
      _ = logger.info(s"Initializing sequencer")
      _ <- withSequencerAdminConnection(node.sequencer.adminApi)(
        _.initializeFromBeginning(
          StoredTopologyTransactionsX(
            bootstrapTransactions
          ),
          staticDomainParameters,
        )
      )
      _ = logger.info(s"Initializing mediator")
      _ <- withMediatorAdminConnection(node.mediator.adminApi)(
        _.initialize(
          domainId,
          staticDomainParameters,
          LocalSynchronizerNode.toSequencerConnection(node.sequencer.internalApi),
        )
      )
    } yield SvSoftDomainMigrationPocResource.InitializeSynchronizerResponse.OK
  }
}
