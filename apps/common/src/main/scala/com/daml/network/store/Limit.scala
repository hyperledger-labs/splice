package com.daml.network.store

sealed trait Limit {
  val limit: Long
}

/** Limit for when the result is expected to not exceed the limit.
  * If exceeded, the store should log a WARN.
  */
case class HardLimit(limit: Long) extends Limit

/** Limit for when the result can be arbitrarily large.
  * To be used with pagination.
  */
case class PageLimit(limit: Long) extends Limit
