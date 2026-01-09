// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.http

import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommand
import org.lfdecentralizedtrust.splice.http.v0.sv_public as httpPublic

object SvHttpClient {
  import httpPublic.SvPublicClient as C
  abstract class BaseCommandPublic[Res, Result] extends HttpCommand[Res, Result, C] {
    override val createGenClientFn = (fn, host, ec, mat) => C.httpClient(fn, host)(ec, mat)
  }
}
