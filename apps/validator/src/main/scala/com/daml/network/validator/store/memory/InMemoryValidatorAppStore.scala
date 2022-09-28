package com.daml.network.validator.store.memory
import com.daml.network.validator.store.ValidatorAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

/** Example for in-memory store in the store pattern. */
class InMemoryValidatorAppStore(
    validatorParty: PartyId,
    svcParty: PartyId,
    walletServiceParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends ValidatorAppStore
    with NamedLogging {

  override def getValidatorParty(): Future[PartyId] = Future {
    validatorParty
  }

  override def getSvcParty(): Future[PartyId] = Future {
    svcParty
  }

  override def getWalletServiceParty(): Future[PartyId] = Future {
    walletServiceParty
  }

  override def close(): Unit = ()
}
