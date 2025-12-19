// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommand
import com.digitalasset.canton.console.ConsoleCommandResult

trait HttpCommandRunner {

  /** Run an HTTP command and return its result.
    * HTTP variant of Canton’s AdminCommandRunner.
    */
  protected[console] def httpCommand[Result](
      httpCommand: HttpCommand[?, Result],
      basePath: Option[String] = None,
  ): ConsoleCommandResult[Result]
}
