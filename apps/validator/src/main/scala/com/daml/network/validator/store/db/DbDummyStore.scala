package com.daml.network.validator.store.db

import com.daml.network.validator.store.DummyStore
import com.digitalasset.canton.tracing.TraceContext

import scala.annotation.nowarn
import scala.concurrent.Future

/** Example for DB-based store in the "Store" pattern.
  */
@nowarn // TODO(Arne): eventually implement - but we won't need this in M1.
class DbDummyStore extends DummyStore {
  override def increment(int: Int)(implicit tc: TraceContext): Future[Int] = ???

  override def close(): Unit = ???
}
