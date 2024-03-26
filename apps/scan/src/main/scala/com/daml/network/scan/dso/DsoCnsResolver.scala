package com.daml.network.scan.dso

import com.daml.network.util.Contract
import com.daml.network.codegen.java.cn.dsorules.DsoRules
import com.daml.network.http.v0.definitions as http
import com.daml.network.scan.dso.DsoCnsResolver.{DsoCnsEntry, dsoCnsName, svCnsNameSuffix}
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

class DsoCnsResolver(dsoParty: PartyId) {
  private val dsoCnsEntry: DsoCnsEntry = DsoCnsEntry(dsoParty, dsoCnsName)
  private val svCnsNamePattern = """^[a-zA-Z0-9_-]+\.sv\.ans$""".r

  def lookupEntryByName(
      dsoRules: Contract[DsoRules.ContractId, DsoRules],
      cnsName: String,
  ): Option[DsoCnsEntry] =
    cnsName match {
      case `dsoCnsName` => Some(dsoCnsEntry)
      case svCnsNamePattern() =>
        val maybeSvPartyId = dsoRules.payload.members.asScala.collectFirst {
          case (svPartyId, memberInfo) if cnsName == svCnsName(memberInfo.name) =>
            PartyId.tryFromProtoPrimitive(svPartyId) -> memberInfo.name
        }
        maybeSvPartyId.map { case (svPartyId, memberName) =>
          svCnsEntry(svPartyId, memberName.toLowerCase())
        }
      case _ => None
    }

  def lookupEntryByParty(
      dsoRules: Contract[DsoRules.ContractId, DsoRules],
      party: PartyId,
  ): Option[DsoCnsEntry] =
    party match {
      case `dsoParty` => Some(dsoCnsEntry)
      case _ =>
        dsoRules.payload.members.asScala
          .get(party.toProtoPrimitive)
          .map(memberInfo => svCnsEntry(party, memberInfo.name))
    }

  def listEntries(
      dsoRules: Contract[DsoRules.ContractId, DsoRules],
      namePrefix: Option[String],
  ): Seq[DsoCnsEntry] = {
    val entries = dsoCnsEntry +: dsoRules.payload.members.asScala.collect {
      case (svParty, memberInfo) =>
        svCnsEntry(PartyId.tryFromProtoPrimitive(svParty), memberInfo.name)
    }.toSeq
    namePrefix.fold(entries)(prefix => entries.filter(_.name.startsWith(prefix)))
  }

  private def svCnsEntry(svParty: PartyId, svName: String) = DsoCnsEntry(svParty, svCnsName(svName))

  private def svCnsName(svName: String) = s"${svName.toLowerCase}$svCnsNameSuffix"
}

object DsoCnsResolver {
  val dsoCnsName = "dso.ans"
  val svCnsNameSuffix = ".sv.ans"
  case class DsoCnsEntry(user: PartyId, name: String) {
    def toHttp: http.CnsEntry = http.CnsEntry(None, user.toProtoPrimitive, name, "", "", None)
  }
}
