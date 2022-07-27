package com.daml.network.wallet.domain

import cats.syntax.either._
import com.daml.ledger.client.binding
import com.daml.ledger.client.binding.Contract
import com.daml.lf.data.Numeric
import com.daml.network.wallet.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.version.HasProtoV0
import com.digitalasset.network.CC.Coin.Coin
import com.digitalasset.network.CN.Wallet

/** Scala-representation of a Paymentrequest with utility methods for conversion from/to protobuf. */
case class PaymentRequest(payer: PartyId, payee: PartyId, svc: PartyId, quantity: BigDecimal)
    extends HasProtoV0[v0.PaymentRequest] {
  override def toProtoV0: v0.PaymentRequest =
    v0.PaymentRequest(
      payer = payer.toProtoPrimitive,
      payee = payee.toProtoPrimitive,
      svc = svc.toProtoPrimitive,
      quantity = quantity.toString,
    )

}

object PaymentRequest {
  def fromProto(reqP: v0.PaymentRequest): Either[ProtoDeserializationError, PaymentRequest] = {
    def partyFromString(str: String): Either[ProtoDeserializationError, PartyId] = PartyId
      .fromProtoPrimitive(str)
      .leftMap(ProtoDeserializationError.StringConversionError)
    def decimalFromString(str: String): Either[ProtoDeserializationError, Numeric] = Numeric
      .fromString(str)
      .leftMap(ProtoDeserializationError.StringConversionError)
    for {
      svc <- partyFromString(reqP.svc)
      payer <- partyFromString(reqP.payer)
      payee <- partyFromString(reqP.payee)
      quantity <- decimalFromString(reqP.quantity)
    } yield PaymentRequest(payer = payer, payee = payee, svc = svc, quantity = quantity)
  }

  /** Creating a Scala PaymentRequest representation from the generated Scala binding class for a Coin template instance */
  def fromContract(req: Wallet.PaymentRequest.PaymentRequest): PaymentRequest = {
    def partyFromPrim(p: binding.Primitive.Party): PartyId =
      PartyId.tryFromProtoPrimitive(p.toString)

    PaymentRequest(
      payer = partyFromPrim(req.payee),
      payee = partyFromPrim(req.payer),
      svc = partyFromPrim(req.svc),
      quantity = req.quantity,
    )
  }
}
