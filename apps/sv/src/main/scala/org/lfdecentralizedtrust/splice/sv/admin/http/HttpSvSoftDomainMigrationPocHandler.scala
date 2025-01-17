// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.http

import org.lfdecentralizedtrust.splice.auth.AuthExtractor.TracedUser
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, SharedSpliceAppParameters}
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.sv_soft_domain_migration_poc as v0
import org.lfdecentralizedtrust.splice.http.v0.sv_soft_domain_migration_poc.SvSoftDomainMigrationPocResource
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.scan.admin.api.client.SingleScanConnection
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.sv.{ExtraSynchronizerNode, LocalSynchronizerNode}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.{CommunityCryptoConfig, CommunityCryptoProvider}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId, UniqueIdentifier}
import com.digitalasset.canton.topology.processing.{EffectiveTime, SequencedTime}
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransaction,
  StoredTopologyTransactions,
  TimeQuery,
  TopologyStoreId,
}
import StoredTopologyTransaction.GenericStoredTopologyTransaction
import com.digitalasset.canton.topology.transaction.TopologyMapping
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.digitalasset.canton.version.ProtocolVersion
import io.grpc.Status
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

// TODO(#13301) Validate that topology reads return the right amount of data
class HttpSvSoftDomainMigrationPocHandler(
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    synchronizerNodes: Map[String, ExtraSynchronizerNode],
    participantAdminConnection: ParticipantAdminConnection,
    migrationId: Long,
    legacyMigrationId: Option[Long],
    clock: Clock,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    val amuletAppParameters: SharedSpliceAppParameters,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends v0.SvSoftDomainMigrationPocHandler[TracedUser]
    with Spanning
    with NamedLogging {

  private val dsoStore = dsoStoreWithIngestion.store

  private def getScanUrls()(implicit tc: TraceContext): Future[Seq[String]] = {
    for {
      // TODO(#13301) We should use the internal URL for the SV’s own scan to avoid a loopback requirement
      dsoRulesWithSvNodeStates <- dsoStore.getDsoRulesWithSvNodeStates()
    } yield dsoRulesWithSvNodeStates.svNodeStates.values
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
  ): Future[Seq[GenericSignedTopologyTransaction]] = for {
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
            TopologyMapping.Code.OwnerToKeyMapping,
            TopologyMapping.Code.NamespaceDelegation,
          ),
          filterNamespace = Some(namespace),
        )
      }
      .map(_.flatten)
    decentralizedNamespaceDefinition <- participantAdminConnection.listAllTransactions(
      TopologyStoreId.DomainStore(decentralizedSynchronizerId),
      TimeQuery.Range(None, None),
      includeMappings = Set(TopologyMapping.Code.DecentralizedNamespaceDefinition),
    )
  } yield (identityTransactions ++ decentralizedNamespaceDefinition).map(_.transaction)

  // Takes a list of (ordered) signed topology transactions and turns them into
  // StoredTopologyTransactions ensuring that only the latest serial has validUntil = None
  private def toStoredTopologyBootstrapTransactions(
      ts: Seq[GenericSignedTopologyTransaction]
  ): Seq[GenericStoredTopologyTransaction] =
    ts.foldRight(
      (Set.empty[TopologyMapping.MappingHash], Seq.empty[GenericStoredTopologyTransaction])
    ) { case (tx, (newerMappings, acc)) =>
      (
        newerMappings + tx.transaction.mapping.uniqueKey,
        StoredTopologyTransaction(
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
      UniqueIdentifier.tryCreate(
        domainIdPrefix,
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
          CommunityCryptoConfig(provider = CommunityCryptoProvider.Jce),
          ProtocolVersion.v32,
        )
        .valueOr(err =>
          throw new IllegalArgumentException(s"Invalid domain parameters config: $err")
        )
      _ = logger.info(s"Initializing sequencer")
      _ <- node.sequencerAdminConnection.initializeFromBeginning(
        StoredTopologyTransactions(
          bootstrapTransactions
        ),
        staticDomainParameters,
      )
      _ = logger.info(s"Initializing mediator")
      _ <- node.mediatorAdminConnection.initialize(
        domainId,
        LocalSynchronizerNode.toSequencerConnection(node.sequencerPublicApi),
      )
    } yield SvSoftDomainMigrationPocResource.InitializeSynchronizerResponse.OK
  }

  override def reconcileSynchronizerDamlState(
      respond: SvSoftDomainMigrationPocResource.ReconcileSynchronizerDamlStateResponse.type
  )(domainIdPrefix: String)(
      extracted: TracedUser
  ): Future[SvSoftDomainMigrationPocResource.ReconcileSynchronizerDamlStateResponse] = {
    implicit val TracedUser(_, traceContext) = extracted
    val domainId = DomainId(
      UniqueIdentifier.tryCreate(
        domainIdPrefix,
        dsoStore.key.dsoParty.uid.namespace,
      )
    )
    val synchronizerNodeReconciler = new SynchronizerNodeReconciler(
      dsoStore,
      dsoStoreWithIngestion.connection,
      legacyMigrationId,
      clock,
      retryProvider,
      logger,
    )
    val node = synchronizerNodes
      .get(domainIdPrefix)
      .getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"No synchronizer node for $domainIdPrefix configured")
          .asRuntimeException()
      )
    synchronizerNodeReconciler
      .reconcileSynchronizerNodeConfigIfRequired(
        Some(node),
        domainId,
        SynchronizerNodeReconciler.SynchronizerNodeState.OnboardedImmediately,
        migrationId,
      )
      .map(_ => SvSoftDomainMigrationPocResource.ReconcileSynchronizerDamlStateResponse.OK)
  }

  override def signDsoPartyToParticipant(
      respond: SvSoftDomainMigrationPocResource.SignDsoPartyToParticipantResponse.type
  )(domainIdPrefix: String)(
      extracted: TracedUser
  ): Future[SvSoftDomainMigrationPocResource.SignDsoPartyToParticipantResponse] = {
    implicit val TracedUser(_, traceContext) = extracted
    val domainId = DomainId(
      UniqueIdentifier.tryCreate(
        domainIdPrefix,
        dsoStore.key.dsoParty.uid.namespace,
      )
    )
    for {
      dsoRules <- dsoStore.getDsoRules()
      participantIds = dsoRules.payload.svs.values.asScala
        .map(sv => ParticipantId.tryFromProtoPrimitive(sv.participantId))
        .toSeq
      // We resign the PartyToParticipant mapping instead of replaying it to limit the topology
      // transactions that need to be transferred across protocol versions to the bare minimum.
      // We retry as it might take a bit until the SV participants are all known on the new domain
      // and until they are this fails topology validation.
      _ <- retryProvider.retryForClientCalls(
        "sign_dso_party_to_participant",
        "sign_dso_party_to_participant",
        participantAdminConnection.proposeInitialPartyToParticipant(
          TopologyStoreId.DomainStore(domainId),
          dsoStore.key.dsoParty,
          participantIds,
          isProposal = true,
        ),
        logger,
      )
    } yield SvSoftDomainMigrationPocResource.SignDsoPartyToParticipantResponse.OK
  }
}
