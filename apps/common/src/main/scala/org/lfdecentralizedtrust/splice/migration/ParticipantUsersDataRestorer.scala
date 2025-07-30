// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection

import scala.concurrent.{ExecutionContext, Future}

class ParticipantUsersDataRestorer(
    ledgerConnection: SpliceLedgerConnection,
    override protected val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  def restoreParticipantUsersData(
      participantUsersData: ParticipantUsersData
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] =
    for {
      _ <- restoreIdentityProviders(participantUsersData.identityProviders)
      _ <- restoreUsers(participantUsersData.users)
    } yield ()

  def restoreIdentityProviders(
      identityProviders: Seq[ParticipantIdentityProvider]
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] =
    for {
      _ <- Future.sequence(
        identityProviders.map(identityProvider => {
          logger.info(s"Restoring identity provider config ${identityProvider.id}")
          ledgerConnection.ensureIdentityProviderConfigCreated(
            identityProvider.id,
            identityProvider.isDeactivated,
            identityProvider.jwksUrl,
            identityProvider.issuer,
            identityProvider.audience,
          )
        })
      )
    } yield ()

  def restoreUsers(users: Seq[ParticipantUser])(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit] = {
    for {
      _ <- Future.sequence(users.map { user =>
        {
          logger.info(s"Restoring user ${user.id}")
          logger.info(s"user's annotations: ${user.annotations}")
          ledgerConnection.ensureUserCreated(
            user.id,
            user.primaryParty,
            user.rights,
            user.isDeactivated,
            user.annotations,
            user.identityProviderId,
          )
        }
      })
    } yield ()
  }
}
