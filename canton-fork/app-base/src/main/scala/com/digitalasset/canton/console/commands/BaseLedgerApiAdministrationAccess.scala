// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console.commands

import com.digitalasset.canton.config

object BaseLedgerApiAdministrationAccess {
  implicit class `BaseLedgerApiAdministration access`(
      private val self: BaseLedgerApiAdministration
  ) extends AnyVal {
    def optionallyAwait[Tx](
        tx: Tx,
        txId: String,
        txSynchronizerId: String,
        optTimeout: Option[config.NonNegativeDuration],
    ): Tx =
      self.optionallyAwait(tx, txId, txSynchronizerId, optTimeout)
  }
}
