// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.migration.{
  ParticipantUsersData,
  ParticipantIdentityProvider,
  ParticipantUser,
}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ParticipantUsersDataExporter(
    ledgerConnection: SpliceLedgerConnection
) {

  def exportParticipantUsersData()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[ParticipantUsersData] =
    for {
      identityProviders <- exportIdentityProviders()
      users <- exportUsers()
    } yield ParticipantUsersData(identityProviders, users)

  private def exportIdentityProviders()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[ParticipantIdentityProvider]] =
    for {
      res <- ledgerConnection.listIdentityProviderConfigs()
    } yield res.map(config =>
      new ParticipantIdentityProvider(
        config.identityProviderId,
        config.isDeactivated,
        config.jwksUrl,
        config.issuer,
        config.audience,
      )
    )

  private def exportUsers()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[ParticipantUser]] =
    for { res <- ledgerConnection.listAllUserData() } yield res.map { case (user, rights) =>
      new ParticipantUser(
        user.getId,
        Option(user.getPrimaryParty).filter(_.nonEmpty).map(PartyId.tryFromProtoPrimitive),
        rights,
        user.getIsDeactivated,
        user.getMetadata.getAnnotationsMap.asScala.toMap,
        Option(user.getIdentityProviderId).filter(_.nonEmpty),
      )
    }
}
