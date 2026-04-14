// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.data.Chain
import com.digitalasset.canton.logging.ErrorLoggingContext
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0

object ContractList {

  /** Given two lists that are sorted by `createdAt` (or a field that is linked to it, such as store insertion order),
    * this will return a list that merges both of them in order. The running cost is O(size(l1) + size(l2)).
    * In case of duplicates by contract id, the entry from l2 wins.
    */
  def mergeSortedContractListsToHttp(l1: Seq[Contract[?, ?]], l2: Seq[Contract[?, ?]])(implicit
      elc: ErrorLoggingContext
  ): Chain[d0.Contract] = {
    @scala.annotation.tailrec
    def go(
        l1: List[Contract[?, ?]],
        l2: List[Contract[?, ?]],
        acc: Chain[d0.Contract],
    ): Chain[d0.Contract] = {
      (l1, l2) match {
        case (Nil, Nil) => acc
        case (remaining, Nil) => acc ++ Chain.fromSeq(remaining.map(_.toHttp))
        case (Nil, remaining) => acc ++ Chain.fromSeq(remaining.map(_.toHttp))
        case (h1 :: t1, h2 :: t2) =>
          if (h1.contractId.contractId == h2.contractId.contractId) {
            go(t1, t2, acc :+ h2.toHttp)
          } else if (h1.createdAt.isBefore(h2.createdAt))
            go(t1, l2, acc :+ h1.toHttp)
          else
            go(l1, t2, acc :+ h2.toHttp)
      }
    }
    go(l1.toList, l2.toList, Chain.empty)
  }

}
