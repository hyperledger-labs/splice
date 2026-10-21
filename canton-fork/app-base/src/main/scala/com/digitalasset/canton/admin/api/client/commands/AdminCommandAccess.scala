// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.admin.api.client.commands

object AdminCommandAccess {
  implicit class `AdminCommand access`[Req, Res, Result](
      private val self: AdminCommand[Req, Res, Result]
  ) extends AnyVal {
    @inline def createRequestInternal(): Either[String, Req] = self.createRequestInternal()

    @inline def handleResponseInternal(response: Res): Either[String, Result] =
      self.handleResponseInternal(response)
  }
}
