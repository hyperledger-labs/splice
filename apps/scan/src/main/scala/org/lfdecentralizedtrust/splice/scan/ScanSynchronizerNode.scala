// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan

import org.lfdecentralizedtrust.splice.environment.{SequencerAdminConnection, SynchronizerNode}

final class ScanSynchronizerNode(
    override val sequencerAdminConnection: SequencerAdminConnection
) extends SynchronizerNode(sequencerAdminConnection)
    with AutoCloseable {
  override def close(): Unit = {
    sequencerAdminConnection.close()
  }
}
