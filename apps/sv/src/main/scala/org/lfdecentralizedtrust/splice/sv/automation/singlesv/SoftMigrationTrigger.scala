// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import cats.syntax.traverse.*
import com.digitalasset.canton.topology.store.{TimeQuery, TopologyStoreId}
import com.digitalasset.canton.topology.transaction.TopologyMapping
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.SingleScanConnection
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait SoftMigrationTrigger extends Trigger {
  def dsoStore: SvDsoStore
  def upgradesConfig: UpgradesConfig

  def getScanUrls()(implicit ec: ExecutionContext, tc: TraceContext): Future[Seq[String]] = {
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

  def withScanConnection[T](
      url: String
  )(f: SingleScanConnection => Future[T])(implicit
      ec: ExecutionContextExecutor,
      httpClient: HttpClient,
      mat: Materializer,
      tc: TraceContext,
      templateJsonDecoder: TemplateJsonDecoder,
  ): Future[T] =
    SingleScanConnection.withSingleScanConnection(
      ScanAppClientConfig(
        NetworkAppClientConfig(
          url
        )
      ),
      upgradesConfig,
      context.clock,
      context.retryProvider,
      loggerFactory,
    )(f)

  def getDecentralizedNamespaceDefinitionTransactions(connection: TopologyAdminConnection)(implicit
      tc: TraceContext
  ): Future[Seq[GenericSignedTopologyTransaction]] = for {
    decentralizedSynchronizerId <- dsoStore.getAmuletRulesDomain()(tc)
    namespaceDefinitions <- connection.listDecentralizedNamespaceDefinition(
      decentralizedSynchronizerId,
      decentralizedSynchronizerId.uid.namespace,
      timeQuery = TimeQuery.Range(None, None),
    )
    identityTransactions <- namespaceDefinitions
      .flatMap(_.mapping.owners)
      .toSet
      .toList
      .traverse { namespace =>
        connection.listAllTransactions(
          TopologyStoreId.SynchronizerStore(decentralizedSynchronizerId),
          TimeQuery.Range(None, None),
          includeMappings = Set(
            TopologyMapping.Code.OwnerToKeyMapping,
            TopologyMapping.Code.NamespaceDelegation,
          ),
          filterNamespace = Some(namespace),
        )
      }
      .map(_.flatten)
    decentralizedNamespaceDefinition <- connection.listAllTransactions(
      TopologyStoreId.SynchronizerStore(decentralizedSynchronizerId),
      TimeQuery.Range(None, None),
      includeMappings = Set(TopologyMapping.Code.DecentralizedNamespaceDefinition),
    )
  } yield (identityTransactions ++ decentralizedNamespaceDefinition).map(_.transaction)

}
