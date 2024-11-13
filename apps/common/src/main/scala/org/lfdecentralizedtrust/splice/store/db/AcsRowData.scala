// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.util.Contract
import slick.jdbc.{PositionedParameters, SetParameter}

trait AcsRowData {
  val contract: Contract[?, ?]
  def contractExpiresAt: Option[Timestamp]
  def indexColumns: Seq[(String, IndexColumnValue[?])]
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

case class IndexColumnValue[V](value: V)(private implicit val setParameter: SetParameter[V])
object IndexColumnValue {
  import scala.language.implicitConversions // convenience
  implicit def conversion[V](value: V)(implicit
      setParameter: SetParameter[V]
  ): IndexColumnValue[V] = IndexColumnValue(value)

  implicit def indexColumnValueSetParameter[V]: SetParameter[IndexColumnValue[V]] =
    (v1: IndexColumnValue[V], v2: PositionedParameters) => v1.setParameter(v1.value, v2)

}
