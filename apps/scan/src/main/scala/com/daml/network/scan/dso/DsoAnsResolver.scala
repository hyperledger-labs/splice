package com.daml.network.scan.dso

import com.daml.network.util.Contract
import com.daml.network.codegen.java.cn.dsorules.DsoRules
import com.daml.network.http.v0.definitions as http
import com.daml.network.scan.dso.DsoAnsResolver.{DsoAnsEntry, dsoAnsName, svAnsNameSuffix}
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

class DsoAnsResolver(dsoParty: PartyId) {
  private val dsoAnsEntry: DsoAnsEntry = DsoAnsEntry(dsoParty, dsoAnsName)
  private val svAnsNamePattern = """^[a-zA-Z0-9_-]+\.sv\.ans$""".r

  def lookupEntryByName(
      dsoRules: Contract[DsoRules.ContractId, DsoRules],
      ansName: String,
  ): Option[DsoAnsEntry] =
    ansName match {
      case `dsoAnsName` => Some(dsoAnsEntry)
      case svAnsNamePattern() =>
        val maybeSvPartyId = dsoRules.payload.members.asScala.collectFirst {
          case (svPartyId, memberInfo) if ansName == svAnsName(memberInfo.name) =>
            PartyId.tryFromProtoPrimitive(svPartyId) -> memberInfo.name
        }
        maybeSvPartyId.map { case (svPartyId, memberName) =>
          svAnsEntry(svPartyId, memberName.toLowerCase())
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
        dsoRules.payload.members.asScala
          .get(party.toProtoPrimitive)
          .map(memberInfo => svAnsEntry(party, memberInfo.name))
    }

  def listEntries(
      dsoRules: Contract[DsoRules.ContractId, DsoRules],
      namePrefix: Option[String],
  ): Seq[DsoAnsEntry] = {
    val entries = dsoAnsEntry +: dsoRules.payload.members.asScala.collect {
      case (svParty, memberInfo) =>
        svAnsEntry(PartyId.tryFromProtoPrimitive(svParty), memberInfo.name)
    }.toSeq
    namePrefix.fold(entries)(prefix => entries.filter(_.name.startsWith(prefix)))
  }

  private def svAnsEntry(svParty: PartyId, svName: String) = DsoAnsEntry(svParty, svAnsName(svName))

  private def svAnsName(svName: String) = s"${svName.toLowerCase}$svAnsNameSuffix"
}

object DsoAnsResolver {
  val dsoAnsName = "dso.ans"
  val svAnsNameSuffix = ".sv.ans"
  case class DsoAnsEntry(user: PartyId, name: String) {
    def toHttp: http.AnsEntry = http.AnsEntry(None, user.toProtoPrimitive, name, "", "", None)
  }
}
