package com.daml.network.store

import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.TraceContext
import cats.syntax.either.*
import io.grpc.Status

sealed trait Limit {
  val limit: Int
}

object Limit {

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
  * To be used with pagination.
  */
case class PageLimit private (limit: Int) extends Limit
object PageLimit {
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
            limit.toLong,
          )
          result.take(limit)
        } else {
          result
        }
    }
  }

  protected def sqlLimit(limit: Limit): Int = {
    limit match {
      case HardLimit(limit) => limit + 1
      case PageLimit(limit) => limit
    }
  }

}
