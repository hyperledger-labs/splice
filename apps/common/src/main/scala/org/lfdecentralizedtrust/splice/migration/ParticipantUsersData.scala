// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import com.daml.ledger.javaapi.data.User
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId

final case class ParticipantUsersData(
    identityProviders: Seq[ParticipantIdentityProvider],
    users: Seq[ParticipantUser],
) extends PrettyPrinting {

  def toHttp: http.ParticipantUsersData = http.ParticipantUsersData(
    identityProviders = identityProviders.map(_.toHttp).toVector,
    users = users.map(_.toHttp).toVector,
  )

  import Pretty.*

  override def pretty: Pretty[ParticipantUsersData.this.type] =
    Pretty.prettyNode(
      "ParticipantUsersData",
      param("identityProviders", _.identityProviders),
      param("users", _.users),
    )
}
object ParticipantUsersData {
  def fromHttp(response: http.ParticipantUsersData): ParticipantUsersData = ParticipantUsersData(
    identityProviders = response.identityProviders.map(ParticipantIdentityProvider.fromHttp),
    users = response.users.map(ParticipantUser.fromHttp),
  )
}

final case class ParticipantIdentityProvider(
    id: String,
    isDeactivated: Boolean,
    jwksUrl: String,
    issuer: String,
    audience: String,
) extends PrettyPrinting {
  def toHttp: http.ParticipantIdentityProvider = http.ParticipantIdentityProvider(
    id,
    isDeactivated,
    jwksUrl,
    issuer,
    audience,
  )

  import Pretty.*

  override def pretty: Pretty[ParticipantIdentityProvider.this.type] =
    Pretty.prettyNode(
      "ParticipantIdentityProvider",
      param("id", _.id.doubleQuoted),
      param("isDeactivated", _.isDeactivated),
      param("jwksUrl", _.jwksUrl.doubleQuoted),
      param("issuer", _.issuer.doubleQuoted),
      param("audience", _.audience.doubleQuoted),
    )
}
object ParticipantIdentityProvider {
  def fromHttp(response: http.ParticipantIdentityProvider): ParticipantIdentityProvider =
    ParticipantIdentityProvider(
      response.id,
      response.isDeactivated,
      response.jwksUrl,
      response.issuer,
      response.audience,
    )
}

final case class ParticipantUser(
    id: String,
    primaryParty: Option[PartyId] = None,
    rights: Seq[User.Right],
    isDeactivated: Boolean = false,
    annotations: Map[String, String] = Map.empty,
    identityProviderId: Option[String] = None,
) extends PrettyPrinting {
  def toHttp: http.ParticipantUser = http.ParticipantUser(
    id,
    primaryParty.map(_.toProtoPrimitive),
    rights.map {
      case actAs: User.Right.CanActAs =>
        http.ParticipantUserRight(http.ParticipantUserRight.Kind.CanActAs, Some(actAs.party))
      case readAs: User.Right.CanReadAs =>
        http.ParticipantUserRight(http.ParticipantUserRight.Kind.CanReadAs, Some(readAs.party))
      case executeAs: User.Right.CanExecuteAs =>
        http.ParticipantUserRight(http.ParticipantUserRight.Kind.CanExecuteAs, Some(executeAs.party))
      case _: User.Right.IdentityProviderAdmin =>
        http.ParticipantUserRight(http.ParticipantUserRight.Kind.IdentityProviderAdmin)
      case _: User.Right.ParticipantAdmin =>
        http.ParticipantUserRight(http.ParticipantUserRight.Kind.ParticipantAdmin)
      case _: User.Right.CanReadAsAnyParty =>
        http.ParticipantUserRight(http.ParticipantUserRight.Kind.CanReadAsAnyParty)
      case _: User.Right.CanExecuteAsAnyParty =>
        http.ParticipantUserRight(http.ParticipantUserRight.Kind.CanExecuteAsAnyParty)
      case _ => throw new IllegalArgumentException("Unsupported user right")
    }.toVector,
    isDeactivated,
    annotations.map { case (k, v) => http.ParticipantUserAnnotation(k, v) }.toVector,
    identityProviderId,
  )

  import Pretty.*

  override def pretty: Pretty[ParticipantUser.this.type] =
    Pretty.prettyNode(
      "ParticipantUser",
      param("id", _.id.doubleQuoted),
      param("primaryParty", _.primaryParty),
      param("rights", _.rights.map(_.toString.doubleQuoted)),
      param("isDeactivated", _.isDeactivated),
      param("annotations", _.annotations.map { case (k, v) => (k.doubleQuoted, v.doubleQuoted) }),
      param("identityProviderId", _.identityProviderId.map(_.doubleQuoted)),
    )
}
object ParticipantUser {
  def fromHttp(response: http.ParticipantUser): ParticipantUser = ParticipantUser(
    response.id,
    response.primaryParty.map(PartyId.tryFromProtoPrimitive),
    response.rights.collect {
      case http.ParticipantUserRight(http.ParticipantUserRight.Kind.CanActAs, Some(party)) =>
        new User.Right.CanActAs(party)
      case http.ParticipantUserRight(http.ParticipantUserRight.Kind.CanReadAs, Some(party)) =>
        new User.Right.CanReadAs(party)
      case http.ParticipantUserRight(http.ParticipantUserRight.Kind.CanExecuteAs, Some(party)) =>
        new User.Right.CanExecuteAs(party)
      case http.ParticipantUserRight(http.ParticipantUserRight.Kind.ParticipantAdmin, None) =>
        User.Right.ParticipantAdmin.INSTANCE
      case http.ParticipantUserRight(http.ParticipantUserRight.Kind.IdentityProviderAdmin, None) =>
        User.Right.IdentityProviderAdmin.INSTANCE
      case http.ParticipantUserRight(http.ParticipantUserRight.Kind.CanReadAsAnyParty, None) =>
        User.Right.CanReadAsAnyParty.INSTANCE
      case http.ParticipantUserRight(http.ParticipantUserRight.Kind.CanExecuteAsAnyParty, None) =>
        User.Right.CanExecuteAsAnyParty.INSTANCE
      case _ => throw new IllegalArgumentException("Unsupported or invalid user right")
    },
    response.isDeactivated,
    response.annotations.map(a => a.key -> a.value).toMap,
    response.identityProviderId,
  )
}
