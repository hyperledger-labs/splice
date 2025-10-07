// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import com.daml.ledger.javaapi.data.User
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.StatusRuntimeException
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class UncachedUserRightsProvider(
    connection: SpliceLedgerConnection
)(implicit tc: TraceContext, ec: ExecutionContext)
    extends UserRightsProvider {
  override def listUserRights(userId: String): Future[Set[User.Right]] =
    connection.listUserRights(userId)
  override def getUser(userName: String): Future[Option[User]] = {
    connection.getUser(userName).transform {
      case Success(user) => Success(Some(user))
      case Failure(e: StatusRuntimeException)
          if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
        Success(None)
      case Failure(e) => Failure(e)
    }

  }
}
