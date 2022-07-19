package com.daml.network.validator.store.memory

import java.util.concurrent.atomic.AtomicInteger

import com.daml.network.validator.store.ValidatorAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}
import java.util.concurrent.atomic.AtomicReference

/** Example for in-memory store in the store pattern. */
class InMemoryValidatorAppStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends ValidatorAppStore
    with NamedLogging {
  private val current = new AtomicInteger(0)
  override def increment(int: Int)(implicit tc: TraceContext): Future[Int] = {
    Future { current.addAndGet(int) }
  }

  private val validatorParty : AtomicReference[Option[PartyId]] = new AtomicReference(None)

  override def setValidatorParty(partyId : PartyId) : Future[Unit] = Future {
    validatorParty.set(Some(partyId))
  }

  override def getValidatorParty() : Future[Option[PartyId]] = Future {
    validatorParty.get()
  }

  override def close(): Unit = ()
}
