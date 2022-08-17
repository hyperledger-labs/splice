package com.daml.network.validator.store.memory
import com.daml.network.validator.store.ValidatorAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

/** Example for in-memory store in the store pattern. */
class InMemoryValidatorAppStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends ValidatorAppStore
    with NamedLogging {

  // TODO(i271): Can drop `Option` here once we have mandatory initialization
  private val validatorParty: AtomicReference[Option[PartyId]] = new AtomicReference(None)
  private val svcParty: AtomicReference[Option[PartyId]] = new AtomicReference(None)

  override def setValidatorParty(partyId: PartyId): Future[Unit] = Future {
    validatorParty.set(Some(partyId))
  }

  override def getValidatorParty(): Future[Option[PartyId]] = Future {
    validatorParty.get()
  }

  override def setSvcParty(partyId: PartyId): Future[Unit] = Future {
    svcParty.set(Some(partyId))
  }

  override def getSvcParty(): Future[Option[PartyId]] = Future {
    svcParty.get()
  }

  override def close(): Unit = ()
}
