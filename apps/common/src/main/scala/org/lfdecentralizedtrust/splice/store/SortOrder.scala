// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder

sealed trait SortOrder {
  def whereEventNumber(compareTo: Long): SQLActionBuilder
  val orderByAcsEventNumber: SQLActionBuilder
}

object SortOrder {

  case object Ascending extends SortOrder {
    override def whereEventNumber(compareTo: Long): SQLActionBuilder =
      sql"event_number > $compareTo"

    override val orderByAcsEventNumber: SQLActionBuilder = sql"order by event_number asc"
  }
  case object Descending extends SortOrder {
    override def whereEventNumber(compareTo: Long): SQLActionBuilder =
      sql"event_number < $compareTo"

    override val orderByAcsEventNumber: SQLActionBuilder = sql"order by event_number desc"
  }
}
