// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.KeyValueStore
import org.lfdecentralizedtrust.splice.store.db.StoreDescriptor

import scala.concurrent.{ExecutionContext, Future}

object ScanKeyValueStore {
  def apply(
      dsoParty: PartyId,
      participantId: ParticipantId,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext,
      lc: ErrorLoggingContext,
      cc: CloseContext,
      tc: TraceContext,
  ): Future[KeyValueStore] = {
    KeyValueStore(
      StoreDescriptor(
        version = 1,
        name = "ScanKeyValueStore",
        party = dsoParty,
        participant = participantId,
        key = Map(
          "dsoParty" -> dsoParty.toProtoPrimitive
        ),
      ),
      storage,
      loggerFactory,
    )
  }
}
