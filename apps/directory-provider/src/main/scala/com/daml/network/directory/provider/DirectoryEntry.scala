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

/** Scala-representation of an DirectoryEntry with utility methods for conversion from/to protobuf. */
case class DirectoryEntry(
    contractId: Primitive.ContractId[codegen.DirectoryEntry],
    user: PartyId,
    name: String,
) extends HasProtoV0[v0.Entry] {
  override def toProtoV0: v0.Entry =
    v0.Entry(ApiTypes.ContractId.unwrap(contractId), user.toProtoPrimitive, name)

}

object DirectoryEntry {
  def fromProto(
      requestP: v0.Entry
  ): Either[ProtoDeserializationError, DirectoryEntry] = {
    val v0.Entry(contractIdP, userP, nameP, _) = requestP
    val contractId = Primitive.ContractId[codegen.DirectoryEntry](contractIdP)
    val user = PartyId.tryFromProtoPrimitive(userP)
    Right(DirectoryEntry(contractId, user, nameP))
  }

  def fromContract(request: Contract[codegen.DirectoryEntry]): DirectoryEntry = {
    val user = PartyId.tryFromPrim(request.value.user)
    DirectoryEntry(
      request.contractId,
      user,
      request.value.name,
    )
  }
}
