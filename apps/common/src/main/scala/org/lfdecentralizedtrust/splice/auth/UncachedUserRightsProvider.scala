// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import com.daml.ledger.javaapi.data.User
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection

import scala.concurrent.{ExecutionContext, Future}

class UncachedUserRightsProvider(
    connection: SpliceLedgerConnection
)(implicit tc: TraceContext, ec: ExecutionContext)
    extends UserRightsProvider {
  override def listUserRights(userId: String): Future[Set[User.Right]] =
    connection.listUserRights(userId)
  override def getUser(userName: String): Future[Option[User]] = {
    connection.getUser(userName).map(Some(_))
  }
}
