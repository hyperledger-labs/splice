// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import cats.syntax.traverse.*
import com.digitalasset.canton.topology.store.{TimeQuery, TopologyStoreId}
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.topology.transaction.TopologyMapping
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.Future

trait SoftMigrationTrigger extends Trigger {
  def dsoStore: SvDsoStore

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
