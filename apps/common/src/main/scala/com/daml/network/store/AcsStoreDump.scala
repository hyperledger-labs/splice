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

    def extractCoin(co: http.Contract): Seq[cc.coin.Coin] = {
      // fixup templateId
      val templateId = Ref.Identifier.assertFromString(co.templateId)
      val fixedTemplateId = Ref.Identifier(
        ccPackageId,
        templateId.qualifiedName,
      )
      val fixedContract = co.copy(templateId = fixedTemplateId.toString())
      // attempt to decode as a: Coin
      Contract
        .fromJson(cc.coin.Coin.COMPANION)(fixedContract)
        .map(_.payload)
        // LockedCoin
        .orElse(Contract.fromJson(cc.coin.LockedCoin.COMPANION)(fixedContract).map(_.payload.coin))
        // ImportCrate, as not all of them might have been imported
        .orElse(
          Contract
            .fromJson(cc.coinimport.ImportCrate.COMPANION)(fixedContract)
            .map(_.payload.payload)
        )
        .toSeq
    }

    for {
      httpCo <- contracts
      coin <- extractCoin(httpCo)
      cmd <- {
        val receiverName =
          if (productionMode) coin.owner
          else {
            // Drop the party name suffix, as we don't migrate the parties to a fresh participant node
            dropPartyNameSuffix(coin.owner)
          }

        new cc.coinimport.ImportCrate(
          svcParty.toProtoPrimitive,
          receiverName,
          productionMode,
          coin, // TODO(#6073): consider embedding the contract-id as well for easier traceability
        ).create().commands().asScala.toSeq
      }
    } yield cmd
  }

  // TODO(#6073): get rid of this party name suffix hackery once we only support the fixed mode
  def dropPartyNameSuffix(partyStr: String): String =
    "-[a-z0-9_]+::.*$".r.replaceFirstIn(partyStr, "")

}
