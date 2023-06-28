package com.daml.network.store

import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.TraceContext

sealed trait Limit {
  val limit: Long
}

object Limit {

  val DefaultLimit: Limit = HardLimit(1000L)

}

/** Limit for when the result is expected to not exceed the limit.
  * If exceeded, the store should log a WARN.
  */
case class HardLimit(limit: Long) extends Limit

/** Limit for when the result can be arbitrarily large.
  * To be used with pagination.
  */
case class PageLimit(limit: Long) extends Limit

trait LimitHelpers { _: NamedLogging =>

  protected def applyLimit[T, S[A] <: scala.collection.IterableOps[A, S, S[A]]](
      limit: Limit,
      result: S[T],
  )(implicit
      traceContext: TraceContext
  ): S[T] = {
    limit match {
      case PageLimit(limit) =>
        result.take(limit.intValue())
      case HardLimit(limit) =>
        val resultSize = result.size
        if (resultSize > limit) {
          logger.warn(
            "Size of the result exceeded the limit. Result size: {}. Limit: {}",
            resultSize.toLong,
            limit,
          )
          result.take(limit.intValue())
        } else {
          result
        }
    }
  }

  protected def sqlLimit(limit: Limit): Long = {
    limit match {
      case HardLimit(limit) => limit + 1
      case PageLimit(limit) => limit
    }
  }

}
