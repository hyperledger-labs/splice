// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.admin.api.client.commands

import io.grpc.ManagedChannel
import io.grpc.stub.AbstractStub

import scala.concurrent.Future

object GrpcAdminCommandAccess {

  /** `S` is inferred as the command's own `Svc`, so the stub returned by `createServiceInternal`
    * can be passed back to `submitRequestInternal` on the same command.
    */
  implicit class `GrpcAdminCommand access`[Req, Res, Result, S <: AbstractStub[S]](
      private val self: GrpcAdminCommand[Req, Res, Result] { type Svc = S }
  ) extends AnyVal {
    @inline def createServiceInternal(channel: ManagedChannel): S =
      self.createServiceInternal(channel)

    @inline def submitRequestInternal(service: S, request: Req): Future[Res] =
      self.submitRequestInternal(service, request)
  }
}
