package com.daml.network.store

import com.daml.ledger.javaapi.data
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.daml.lf.data.Ref
import com.daml.network.codegen.java.cc
import com.daml.network.http.v0.definitions as http
import com.daml.network.util.Contract.Companion
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

object AcsStoreDump {

  private def fromJsonIgnoringPackageId[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(contract: http.Contract)(implicit
      decoder: TemplateJsonDecoder
  ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
    val fixedPackageId = Ref.PackageId.assertFromString(companion.TEMPLATE_ID.getPackageId)
    // fixup templateId
    val templateId = Ref.Identifier.assertFromString(contract.templateId)
    val fixedTemplateId = Ref.Identifier(
      fixedPackageId,
      templateId.qualifiedName,
    )
    val fixedContract = contract.copy(templateId = fixedTemplateId.toString())
    Contract.fromJson(companion)(fixedContract)
  }

  def extractOpenMiningRounds(
      contracts: Seq[http.Contract]
  )(implicit templateDecoder: TemplateJsonDecoder): Seq[cc.round.OpenMiningRound] =
    contracts.collect(
      Function.unlift(co =>
        fromJsonIgnoringPackageId(cc.round.OpenMiningRound.COMPANION)(co).toOption.map(_.payload)
      )
    )

  def extractImportCommands(svcParty: PartyId, productionMode: Boolean)(
      contracts: Seq[http.Contract]
  )(implicit templateDecoder: TemplateJsonDecoder): Seq[data.Command] = {

    def extractCoin(co: http.Contract): Seq[cc.coin.Coin] = {
      // attempt to decode as a: Coin
      fromJsonIgnoringPackageId(cc.coin.Coin.COMPANION)(co)
        .map(_.payload)
        // LockedCoin
        .orElse(fromJsonIgnoringPackageId(cc.coin.LockedCoin.COMPANION)(co).map(_.payload.coin))
        // ImportCrate, as not all of them might have been imported
        .orElse(
          fromJsonIgnoringPackageId(cc.coinimport.ImportCrate.COMPANION)(co)
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
          coin, // TODO(#6503): embed the contract id here for idempotent imports
        ).create().commands().asScala.toSeq
      }
    } yield cmd
  }

  // TODO(#6278): get rid of this party name suffix hackery once we only support the fixed mode
  def dropPartyNameSuffix(partyStr: String): String =
    "-[a-z0-9_]+::.*$".r.replaceFirstIn(partyStr, "")

}
