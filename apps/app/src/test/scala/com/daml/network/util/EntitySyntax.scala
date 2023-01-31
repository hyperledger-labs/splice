// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates
//
// Proprietary code. All rights reserved.

package com.daml.network.util

import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.console.{
  LocalDomainReference,
  LocalParticipantReference,
  ParticipantReference,
}
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.topology.*

/** Additional syntactic sugar for domains, participants, parties, and packages
  * Copied from Canton's enterprise code base.
  */
trait EntitySyntax {
  this: BaseTest =>

  val defaultParticipant: String

  implicit class ParticipantReferenceSyntax(participantReference: ParticipantReference)(implicit
      env: CoinTestConsoleEnvironment
  ) {
    def uid: UniqueIdentifier = participantReference.id.uid

    def myParties(filterDomain: String = ""): Set[PartyId] = {
      participantReference.parties
        .hosted(filterDomain = filterDomain)
        .map(_.party)
        .toSet
    }

    def fingerprint: Fingerprint = participantReference.id.fingerprint
  }

  implicit class ParticipantIdSyntax(participantId: ParticipantId)(implicit
      env: CoinTestConsoleEnvironment
  ) {

    import env.*

    def toReference: LocalParticipantReference =
      participants.local.find(_.id == participantId).value

    def fingerprint: Fingerprint = participantId.uid.namespace.fingerprint

    def parties(filterDomain: String = ""): Set[PartyId] =
      participantId.toReference.myParties(filterDomain)
  }

  implicit class PartyIdSyntax(partyId: PartyId)(implicit env: CoinTestConsoleEnvironment) {
    def participants(
        requestingParticipant: LocalParticipantReference,
        filterDomain: String = "",
    ): Set[ParticipantId] =
      requestingParticipant.parties
        .list(filterParty = partyId.filterString, filterDomain = filterDomain)
        .flatMap(_.participants.map(_.participant))
        .toSet
  }

  implicit class StringConversions(name: String)(implicit env: CoinTestConsoleEnvironment) {
    import env.*

    def toDomainRef: LocalDomainReference = d(name)

    def toPartyId(
        requestingParticipant: ParticipantReference = defaultParticipant.toParticipantRef
    ): PartyId = {
      // Query party
      val candidateParties =
        requestingParticipant.topology.party_to_participant_mappings
          .list(filterParty = name + SafeSimpleString.delimiter)
          .map(_.item.party)
          .toSet

      // Create result
      withClue(s"extracting the party with name $name") {
        candidateParties.loneElement
      }
    }

    def toParticipantRef: LocalParticipantReference = p(name)

    def toParticipantId: ParticipantId = toParticipantRef.id
  }

  implicit class SeqConversions(names: Seq[String])(implicit
      env: CoinTestConsoleEnvironment
  ) {
    def toDomainRef: Seq[LocalDomainReference] = names.map(_.toDomainRef)

    def toPartyId(requestingParticipant: LocalParticipantReference): Seq[PartyId] =
      names.map(_.toPartyId(requestingParticipant))

    def toParticipantRef: Seq[LocalParticipantReference] = names.map(_.toParticipantRef)

    def toParticipantId: Seq[ParticipantId] = names.map(_.toParticipantId)
  }

}
