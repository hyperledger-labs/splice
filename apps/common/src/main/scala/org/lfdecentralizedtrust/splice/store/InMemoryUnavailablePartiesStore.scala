// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

// TODO(#6817): Remove in-memory in favor of DbUnavailablePartiesStore
class InMemoryUnavailablePartiesStore(initialParties: Set[PartyId])
    extends UnavailablePartiesStore {

  private val parties: ConcurrentHashMap.KeySetView[PartyId, java.lang.Boolean] = {
    val set = ConcurrentHashMap.newKeySet[PartyId]()
    set.addAll(initialParties.asJava)
    set
  }

  override def addParties(newParties: Seq[PartyId])(implicit
      tc: TraceContext
  ): Future[Unit] = {
    newParties.foreach(parties.add)
    Future.unit
  }

  override def removeParties(toRemove: Seq[PartyId])(implicit
      tc: TraceContext
  ): Future[Seq[PartyId]] =
    Future.successful(toRemove.distinct.filter(parties.remove))

  override def listParties()(implicit tc: TraceContext): Future[Seq[PartyId]] =
    Future.successful(parties.asScala.toSeq)
}
