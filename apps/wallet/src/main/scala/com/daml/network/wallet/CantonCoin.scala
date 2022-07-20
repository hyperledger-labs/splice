package com.daml.network.wallet

import com.daml.ledger.client.binding.Contract
import com.daml.network.examples.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.version.HasProtoV0
import com.digitalasset.network.CC.Coin.Coin

/** Scala-representation of a Coin with utility methods for conversion from/to protobuf. */
case class CantonCoin(svc: PartyId, owner: PartyId, quantity: ExpiringQuantity)
    extends HasProtoV0[v0.Coin] {
  override def toProtoV0: v0.Coin =
    v0.Coin(Some(svc.toProtoPrimitive), Some(owner.toProtoPrimitive), Some(quantity.toProtoV0))

}

object CantonCoin {
  def fromProto(coinP: v0.Coin): Either[ProtoDeserializationError, CantonCoin] = {
    val v0.Coin(svcP, ownerP, quantityP, _) = coinP
    for {
      // TODO(Arne): remove `try`s here
      svc <- ProtoConverter.required("svc", svcP).map(PartyId.tryFromProtoPrimitive)
      owner <- ProtoConverter.required("owner", ownerP).map(PartyId.tryFromProtoPrimitive)
      quantityP2 <- ProtoConverter
        .required("expiringQuantity", quantityP)
      quan <- ExpiringQuantity.fromProto(quantityP2)
    } yield CantonCoin(svc, owner, quan)
  }

  /** Creating a Scala CantonCoin representation from the generated Scala binding class for a Coin template instance */
  def fromContract(coin: Contract[Coin]): CantonCoin = {
    val coinValue = coin.value
    val quantity = coin.value.quantity
    val svc = PartyId.tryFromProtoPrimitive(coinValue.svc.toString)
    val owner = PartyId.tryFromProtoPrimitive(coinValue.owner.toString)
    CantonCoin(
      svc,
      owner,
      ExpiringQuantity(
        quantity.initialQuantity.doubleValue,
        quantity.createdAt.number,
        quantity.ratePerRound.rate.doubleValue,
      ),
    )
  }
}

case class ExpiringQuantity(initialQuantity: Double, createdAt: Long, ratePerRound: Double)
    extends HasProtoV0[v0.ExpiringQuantity] {
  override def toProtoV0: v0.ExpiringQuantity = v0.ExpiringQuantity(
    initialQuantity,
    createdAt,
    ratePerRound,
  )
}

object ExpiringQuantity {
  def fromProto(
      quantityP: v0.ExpiringQuantity
  ): Either[ProtoDeserializationError, ExpiringQuantity] = {
    val v0.ExpiringQuantity(initial, createdAt, rate, _) = quantityP
    Right(ExpiringQuantity(initial, createdAt, rate))
  }

}
