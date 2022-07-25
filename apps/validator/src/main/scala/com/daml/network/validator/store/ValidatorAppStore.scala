package com.daml.network.validator.store

import com.daml.network.validator.store.memory.InMemoryValidatorAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

/** Example for "Store" pattern. */
trait ValidatorAppStore extends AutoCloseable {
  def setSvcParty(partyId: PartyId): Future[Unit]
  def getSvcParty(): Future[Option[PartyId]]
  def setValidatorParty(partyId: PartyId): Future[Unit]
  def getValidatorParty(): Future[Option[PartyId]]
}

object ValidatorAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): ValidatorAppStore =
    storage match {
      case _: MemoryStorage => new InMemoryValidatorAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
