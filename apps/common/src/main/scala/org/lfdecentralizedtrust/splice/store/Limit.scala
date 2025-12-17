// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.TraceContext
import cats.syntax.either.*
import io.grpc.Status

sealed trait Limit {
  val limit: Int
}

object Limit {

  // TODO (#767): make configurable
  val MaxPageSize: Int = 1000
  val DefaultLimit: Limit = HardLimit.tryCreate(MaxPageSize)

  private[store] def validateLimit(limit: Int): Either[String, Int] = {
    if (limit > MaxPageSize) Left(s"Exceeded limit maximum ($limit > $MaxPageSize).")
    else if (limit < 1) Left("Limit must be at least 1.")
    else Right(limit)
  }

}

/** Limit for when the result is expected to not exceed the limit.
  * If exceeded, the store should log a WARN.
  */
case class HardLimit private (limit: Int) extends Limit
object HardLimit {
  def apply(limit: Int): Either[String, HardLimit] = {
    Limit.validateLimit(limit).map(new HardLimit(_))
  }
  def tryCreate(limit: Int): HardLimit =
    HardLimit(limit).valueOr(err =>
      throw Status.INVALID_ARGUMENT
        .withDescription(err)
        .asRuntimeException()
    )

}

/** Limit for when the result can be arbitrarily large (up to Limit.MaxPageSize).
  * To be used with pagination.  A limit of ''n'' implies that if there are
  * ''k > n'' result elements, ''z'' elements where ''0≤z≤n'' may be returned.
  */
case class PageLimit private (limit: Int) extends Limit
object PageLimit {
  val Max: PageLimit = new PageLimit(Limit.MaxPageSize)
  def apply(limit: Int): Either[String, PageLimit] = {
    Limit.validateLimit(limit).map(new PageLimit(_))
  }
  def tryCreate(limit: Int): PageLimit =
    PageLimit(limit).valueOr(err =>
      throw Status.INVALID_ARGUMENT
        .withDescription(err)
        .asRuntimeException()
    )
}

/** Limit with no constraints. Must not be used for production, use only for testing.
  */
case class UnboundLimit private (limit: Int) extends Limit
object UnboundLimit {
  def apply(limit: Int): UnboundLimit = new UnboundLimit(limit)
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
      case HardLimit(limit) =>
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
      case _ =>
        result.take(limit.limit.intValue())
    }
  }

  protected final def applyLimitOrFail[CC[_], C](
      name: String,
      limit: Limit,
      result: C & scala.collection.IterableOps[?, CC, C],
  ): C = {
    limit match {
      case HardLimit(limit) =>
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
      case _ =>
        result.take(limit.limit.intValue())
    }
  }

  protected def sqlLimit(limit: Limit): Int = {
    limit match {
      case HardLimit(limit) => limit + 1
      case _ => limit.limit
    }
  }

}
