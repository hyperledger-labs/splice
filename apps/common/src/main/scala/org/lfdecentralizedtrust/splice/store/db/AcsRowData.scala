// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.google.protobuf.ByteString
import io.circe.Json
import org.lfdecentralizedtrust.splice.util.Contract
import slick.jdbc.{PositionedParameters, SetParameter}

import java.time.Instant

trait AcsRowData {
  val identifier: Identifier
  val contractId: ContractId[?]
  val payload: Json
  val createdEventBlob: ByteString
  val createdAt: Instant
  def contractExpiresAt: Option[Timestamp]
  def indexColumns: Seq[(String, IndexColumnValue[?])]
}

object AcsRowData {
  trait AcsRowDataFromContract extends AcsRowData {
    val contract: Contract[?, ?]
    override val identifier: Identifier = contract.identifier
    override val contractId: ContractId[?] = contract.contractId
    override val payload: Json = AcsJdbcTypes.payloadJsonFromDefinedDataType(contract.payload)
    override val createdEventBlob: ByteString = contract.createdEventBlob
    override val createdAt: Instant = contract.createdAt
  }
  case class AcsRowDataFromInterface(
      identifier: Identifier,
      contractId: ContractId[?],
      payload: Json,
      createdEventBlob: ByteString,
      createdAt: Instant,
  ) extends AcsRowData {
    override def contractExpiresAt: Option[Timestamp] = None

    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq.empty
  }
}

trait TxLogRowData {
  def indexColumns: Seq[(String, IndexColumnValue[?])]
}
object TxLogRowData {
  final object TxLogRowDataWithoutIndices extends TxLogRowData {
    def indexColumns: Seq[Nothing] = Seq.empty
  }
  def noIndices = TxLogRowDataWithoutIndices
}

trait AcsInterfaceViewRowData {
  val interfaceId: Identifier
  val interfaceView: Json
  def indexColumns: Seq[(String, IndexColumnValue[?])]
}
object AcsInterfaceViewRowData {

  /** Just a helper trait for when a store doesn't care about interfaces.
    */
  trait NoInterfacesIngested extends AcsInterfaceViewRowData
}

case class IndexColumnValue[V](value: V)(private implicit val setParameter: SetParameter[V])
object IndexColumnValue {
  import scala.language.implicitConversions // convenience
  implicit def conversion[V](value: V)(implicit
      setParameter: SetParameter[V]
  ): IndexColumnValue[V] = IndexColumnValue(value)

  implicit def indexColumnValueSetParameter[V]: SetParameter[IndexColumnValue[V]] =
    (v1: IndexColumnValue[V], v2: PositionedParameters) => v1.setParameter(v1.value, v2)

}
