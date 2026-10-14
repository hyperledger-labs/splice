// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

/** The kind of bulk storage objects a progress query is about.
  *
  * Update segments and ACS snapshots are written independently, so a Scan can be up to date for one kind and behind
  * for the other. An SV that joins after genesis, for example, writes its own update segments while its ACS snapshots
  * still wait for their seed. Peers ask for the kind they need, so such a Scan still counts as a peer for update
  * segments.
  */
sealed trait BulkObjectKind extends Product with Serializable

object BulkObjectKind {
  case object Updates extends BulkObjectKind
  case object Snapshots extends BulkObjectKind
}
