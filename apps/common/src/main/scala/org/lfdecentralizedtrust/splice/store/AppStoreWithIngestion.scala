// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

/** A trait for stores that have been wired up with an ingestion pipeline.
  *
  * We use this trait to expose both the store and a [[org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection]]
  * whose command submission calls wait for the store to have ingested their effects.
  *
  * We recommend using that connection for executing all command submissions that
  * depend on reads from the store to avoid synchronization issues like #4536
  */
trait AppStoreWithIngestion[Store <: AppStore] {

  /** The store setup with ingestion. */
  def store: Store

  /** A ledger connection whose command submission waits for ingestion into the store.
    * @param submissionPriority affects how aggressive is the circuit breaker in stopping submissions.
    */
  def connection(submissionPriority: SpliceLedgerConnectionPriority): SpliceLedgerConnection
}

object AppStoreWithIngestion {

  sealed trait SpliceLedgerConnectionPriority

  object SpliceLedgerConnectionPriority {

    case object High extends SpliceLedgerConnectionPriority
    case object Medium extends SpliceLedgerConnectionPriority
    case object Low extends SpliceLedgerConnectionPriority
    // Amulet expiry is different from essentially any other trigger run in the SV app in that for it to complete successfully
    // we need a confirmation from the node hosting the amulet owner. So in other words, if a node is down
    // this will start failing. Therefore, we use a dedicated circuit breaker just for amulet expiry
    // to avoid this causing issues for other triggers.
    case object AmuletExpiry extends SpliceLedgerConnectionPriority

  }
}
