package com.daml.network.directory.provider

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.{Contract, Primitive}
import com.daml.network.directory_provider.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.version.HasProtoV0
import com.digitalasset.network.CN.{Directory => codegen}
import com.daml.lf.data.Numeric
import cats.syntax.either._
import scala.concurrent.Future

/** Scala-representation of a DirectoryEntryRequest with utility methods for conversion from/to protobuf. */
case class DirectoryEntryRequest(
    contractId: Primitive.ContractId[codegen.DirectoryEntryRequest],
    user: PartyId,
    name: String,
    fee: BigDecimal,
) extends HasProtoV0[v0.EntryRequest] {
  override def toProtoV0: v0.EntryRequest =
    v0.EntryRequest(
      ApiTypes.ContractId.unwrap(contractId),
      user.toProtoPrimitive,
      name,
      Numeric.toString(fee.bigDecimal),
    )

}

object DirectoryEntryRequest {
  def fromProto(
      requestP: v0.EntryRequest
  ): Either[ProtoDeserializationError, DirectoryEntryRequest] = {
    val v0.EntryRequest(contractIdP, userP, nameP, feeP, _) = requestP
    val contractId = Primitive.ContractId.apply[codegen.DirectoryEntryRequest](contractIdP)
    val user = PartyId.tryFromProtoPrimitive(userP)
    val name = nameP
    for {
      fee <- Numeric.fromString(feeP).leftMap(ProtoDeserializationError.StringConversionError)
    } yield DirectoryEntryRequest(contractId, user, name, fee)
  }

  def fromContract(request: Contract[codegen.DirectoryEntryRequest]): DirectoryEntryRequest = {
    val user = PartyId.tryFromProtoPrimitive(ApiTypes.Party.unwrap(request.value.entry.user))
    DirectoryEntryRequest(
      contractId = request.contractId,
      user = user,
      name = request.value.entry.name,
      fee = request.value.entryFee,
    )
  }
}
