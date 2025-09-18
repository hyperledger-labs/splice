// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import com.daml.ledger.javaapi.data.User

import scala.concurrent.Future

trait UserRightsProvider {
  def listUserRights(userName: String): Future[Set[User.Right]]
  def getUser(userName: String): Future[Option[User]]
}
