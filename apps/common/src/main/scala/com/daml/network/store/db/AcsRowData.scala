package com.daml.network.store.db

import com.daml.lf.data.Time.Timestamp
import com.daml.network.store.TxLogStoreNew
import com.daml.network.util.Contract
import slick.jdbc.{PositionedParameters, SetParameter}

trait AcsRowData {
  val contract: Contract[?, ?]
  def contractExpiresAt: Option[Timestamp]
  def indexColumns: Seq[(String, IndexColumnValue[?])]
}

trait TxLogRowData {
  val entry: TxLogStoreNew.Entry
  def indexColumns: Seq[(String, IndexColumnValue[?])]
}
object TxLogRowData {
  final case class TxLogRowDataWithoutIndices(
      override val entry: TxLogStoreNew.Entry
  ) extends TxLogRowData {
    def indexColumns = Seq.empty
  }
  def noIndices(entry: TxLogStoreNew.Entry) = TxLogRowDataWithoutIndices(entry)
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
