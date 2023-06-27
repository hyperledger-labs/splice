package com.daml.network.store

import com.daml.ledger.javaapi.data
import com.daml.lf.data.Ref
import com.daml.network.codegen.java.cc
import com.daml.network.http.v0.definitions as http
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

object AcsStoreDump {

  def extractImportCommandsFromJsonDump(svcParty: PartyId, productionMode: Boolean)(
      contracts: Seq[http.Contract]
  )(implicit templateDecoder: TemplateJsonDecoder): Seq[data.Command] = {
    val ccPackageId = Ref.PackageId.assertFromString(cc.coin.Coin.TEMPLATE_ID.getPackageId)

    def extractCoin(co: http.Contract) = {
      // fixup templateId
      val templateId = Ref.Identifier.assertFromString(co.templateId)
      val fixedTemplateId = Ref.Identifier(
        ccPackageId,
        templateId.qualifiedName,
      )
      val fixedContract = co.copy(templateId = fixedTemplateId.toString())
      Contract.fromJson(cc.coin.Coin.COMPANION)(fixedContract).toSeq
      // TODO(#6183): support locked contracts
    }

    for {
      httpCo <- contracts
      coin <- extractCoin(httpCo)
      cmd <- {
        val receiverName =
          if (productionMode) coin.payload.owner
          else {
            // Drop the party name suffix, as we don't migrate the parties to a fresh participant node
            dropPartyNameSuffix(coin.payload.owner)
          }

        new cc.coinimport.ImportCrate(
          svcParty.toProtoPrimitive,
          receiverName,
          productionMode,
          coin.payload, // TODO(#6073): consider embedding the contract-id as well for easier traceability
        ).create().commands().asScala.toSeq
      }
    } yield cmd
  }

  // TODO(#6073): get rid of this party name suffix hackery once we only support the fixed mode
  def dropPartyNameSuffix(partyStr: String): String =
    "-[a-z0-9_]+::.*$".r.replaceFirstIn(partyStr, "")

}
