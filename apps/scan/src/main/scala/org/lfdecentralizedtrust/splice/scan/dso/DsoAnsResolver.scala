// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.dso

import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver.DsoAnsEntry
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

class DsoAnsResolver(dsoParty: PartyId, ansAcronym: String) {
  val dsoAnsName = s"dso.$ansAcronym"
  val svAnsNameSuffix = s".sv.$ansAcronym"

  private val dsoAnsEntry: DsoAnsEntry = DsoAnsEntry(dsoParty, dsoAnsName)
  private val svAnsNamePattern = s"^[a-zA-Z0-9_-]+\\.sv\\.$ansAcronym$$".r

  def lookupEntryByName(
      dsoRules: Contract[DsoRules.ContractId, DsoRules],
      ansName: String,
  ): Option[DsoAnsEntry] =
    ansName match {
      case `dsoAnsName` => Some(dsoAnsEntry)
      case svAnsNamePattern() =>
        val maybeSvPartyId = dsoRules.payload.svs.asScala.collectFirst {
          case (svPartyId, svInfo) if ansName == svAnsName(svInfo.name) =>
            PartyId.tryFromProtoPrimitive(svPartyId) -> svInfo.name
        }
        maybeSvPartyId.map { case (svPartyId, svName) =>
          svAnsEntry(svPartyId, svName.toLowerCase())
        }
      case _ => None
    }

  def lookupEntryByParty(
      dsoRules: Contract[DsoRules.ContractId, DsoRules],
      party: PartyId,
  ): Option[DsoAnsEntry] =
    party match {
      case `dsoParty` => Some(dsoAnsEntry)
      case _ =>
        dsoRules.payload.svs.asScala
          .get(party.toProtoPrimitive)
          .map(svInfo => svAnsEntry(party, svInfo.name))
    }

  def listEntries(
      dsoRules: Contract[DsoRules.ContractId, DsoRules],
      namePrefix: Option[String],
  ): Seq[DsoAnsEntry] = {
    val entries = dsoAnsEntry +: dsoRules.payload.svs.asScala.collect { case (svParty, svInfo) =>
      svAnsEntry(PartyId.tryFromProtoPrimitive(svParty), svInfo.name)
    }.toSeq
    namePrefix.fold(entries)(prefix => entries.filter(_.name.startsWith(prefix)))
  }

  private def svAnsEntry(svParty: PartyId, svName: String) = DsoAnsEntry(svParty, svAnsName(svName))

  private def svAnsName(svName: String) = s"${svName.toLowerCase}$svAnsNameSuffix"
}

object DsoAnsResolver {
  def dsoAnsName(ansAcronym: String): String = s"dso.$ansAcronym"
  def svAnsNameSuffix(ansAcronym: String): String = s".sv.$ansAcronym"
  case class DsoAnsEntry(user: PartyId, name: String) {
    def toHttp: http.AnsEntry = http.AnsEntry(None, user.toProtoPrimitive, name, "", "", None)
  }
}
