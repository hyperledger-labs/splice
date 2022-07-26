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

/** Scala-representation of a DirectoryInstallRequest with utility methods for conversion from/to protobuf. */
case class DirectoryInstallRequest(
    contractId: Primitive.ContractId[codegen.DirectoryInstallRequest],
    user: PartyId,
) extends HasProtoV0[v0.InstallRequest] {
  override def toProtoV0: v0.InstallRequest =
    v0.InstallRequest(ApiTypes.ContractId.unwrap(contractId), user.toProtoPrimitive)

}

object DirectoryInstallRequest {
  def fromProto(
      requestP: v0.InstallRequest
  ): Either[ProtoDeserializationError, DirectoryInstallRequest] = {
    val v0.InstallRequest(contractIdP, userP, _) = requestP
    val contractId = Primitive.ContractId.apply[codegen.DirectoryInstallRequest](contractIdP)
    val user = PartyId.tryFromProtoPrimitive(userP)
    Right(DirectoryInstallRequest(contractId, user))
  }

  def fromContract(request: Contract[codegen.DirectoryInstallRequest]): DirectoryInstallRequest = {
    val user = PartyId.tryFromProtoPrimitive(request.value.user.toString)
    DirectoryInstallRequest(
      request.contractId,
      user,
    )
  }
}
