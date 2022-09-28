package com.daml.network.validator.store

import com.daml.network.validator.store.memory.InMemoryValidatorAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

/** Example for "Store" pattern. */
trait ValidatorAppStore extends AutoCloseable {
  def getSvcParty(): Future[PartyId]
  def getValidatorParty(): Future[PartyId]
  def getWalletServiceParty(): Future[PartyId]
}

object ValidatorAppStore {
  def apply(
      validatorParty: PartyId,
      svcParty: PartyId,
      walletServiceParty: PartyId,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext
  ): ValidatorAppStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryValidatorAppStore(validatorParty, svcParty, walletServiceParty, loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
