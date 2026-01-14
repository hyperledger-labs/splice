// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.TraceContext
import cats.syntax.either.*
import io.grpc.Status

sealed trait Limit {
  val limit: Int
  // TODO (#767): make configurable (it's currently an argument, but not user-configurable e.g. via config values)
  val maxPageSize: Int
}

object Limit {

  val DefaultMaxPageSize: Int = 1000
  val DefaultLimit: Limit = HardLimit.tryCreate(DefaultMaxPageSize)

  private[store] def validateLimit(limit: Int, maxPageSize: Int): Either[String, Int] = {
    if (limit > maxPageSize) Left(s"Exceeded limit maximum ($limit > $maxPageSize).")
    else if (limit < 1) Left("Limit must be at least 1.")
    else Right(limit)
  }

}

/** Limit for when the result is expected to not exceed the limit.
  * If exceeded, the store should log a WARN.
  */
case class HardLimit private (limit: Int, maxPageSize: Int) extends Limit
object HardLimit {
  def apply(limit: Int, maxPageSize: Int = Limit.DefaultMaxPageSize): Either[String, HardLimit] = {
    Limit.validateLimit(limit, maxPageSize).map(new HardLimit(_, maxPageSize))
  }
  def tryCreate(limit: Int, maxPageSize: Int = Limit.DefaultMaxPageSize): HardLimit =
    HardLimit(limit, maxPageSize).valueOr(err =>
      throw Status.INVALID_ARGUMENT
        .withDescription(err)
        .asRuntimeException()
    )

}

/** Limit for when the result can be arbitrarily large (up to Limit.MaxPageSize).
  * To be used with pagination.  A limit of ''n'' implies that if there are
  * ''k > n'' result elements, ''z'' elements where ''0≤z≤n'' may be returned.
  */
case class PageLimit private (limit: Int, maxPageSize: Int) extends Limit
object PageLimit {
  val Max: PageLimit = new PageLimit(Limit.DefaultMaxPageSize, Limit.DefaultMaxPageSize)
  def apply(limit: Int, maxPageSize: Int = Limit.DefaultMaxPageSize): Either[String, PageLimit] = {
    Limit.validateLimit(limit, maxPageSize).map(new PageLimit(_, maxPageSize))
  }
  def tryCreate(limit: Int, maxPageSize: Int = Limit.DefaultMaxPageSize): PageLimit =
    PageLimit(limit, maxPageSize).valueOr(err =>
      throw Status.INVALID_ARGUMENT
        .withDescription(err)
        .asRuntimeException()
    )
}

trait LimitHelpers { _: NamedLogging =>

  protected final def applyLimit[CC[_], C](
      name: String,
      limit: Limit,
      result: C & scala.collection.IterableOps[?, CC, C],
  )(implicit
      traceContext: TraceContext
  ): C = {
    limit match {
      case PageLimit(limit, _) =>
        result.take(limit.intValue())
      case HardLimit(limit, _) =>
        val resultSize = result.size
        if (resultSize > limit) {
          logger.warn(
            s"Size of the result exceeded the limit in {}. Result size: {}. Limit: {}",
            name,
            resultSize.toLong,
            limit.toLong,
          )
          result.take(limit)
        } else {
          result
        }
    }
  }

  protected final def applyLimitOrFail[CC[_], C](
      name: String,
      limit: Limit,
      result: C & scala.collection.IterableOps[?, CC, C],
  ): C = {
    limit match {
      case PageLimit(limit, _) =>
        result.take(limit.intValue())
      case HardLimit(limit, _) =>
        val resultSize = result.size
        if (resultSize > limit) {
          throw io.grpc.Status.FAILED_PRECONDITION
            .withDescription(
              s"Size of the result exceeded the limit in $name. Result size: ${resultSize.toLong}. Limit: ${limit.toLong}"
            )
            .asRuntimeException()
        } else {
          result
        }
    }
  }

  protected def sqlLimit(limit: Limit): Int = {
    limit match {
      case PageLimit(limit, _) => limit
      case HardLimit(limit, _) => limit + 1
    }
  }

}
