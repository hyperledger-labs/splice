// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console

import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand

object LedgerApiCommandRunnerAccess {
  implicit class `LedgerApiCommandRunner access`(private val self: LedgerApiCommandRunner)
      extends AnyVal {
    def ledgerApiCommand[Result](
        command: GrpcAdminCommand[?, ?, Result]
    ): ConsoleCommandResult[Result] =
      self.ledgerApiCommand(command)
  }
}
