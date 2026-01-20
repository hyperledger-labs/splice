package org.lfdecentralizedtrust.splice.validator.store

import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.{KeyValueStore, KeyValueStoreDbTableConfig}
import org.lfdecentralizedtrust.splice.store.db.StoreDescriptor

import scala.concurrent.{ExecutionContext, Future}

object ValidatorInternalStore {
  def apply(
      participantId: ParticipantId,
      validatorParty: PartyId,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext,
      lc: ErrorLoggingContext,
      cc: CloseContext,
      tc: TraceContext,
  ): Future[KeyValueStore] = {
    KeyValueStore(
      StoreDescriptor(
        version = 2,
        name = "DbValidatorInternalConfigStore",
        party = validatorParty,
        participant = participantId,
        key = Map(
          "validatorParty" -> validatorParty.toProtoPrimitive
        ),
      ),
      KeyValueStoreDbTableConfig("validator_internal_config", "config_key", "config_value"),
      storage,
      loggerFactory,
    )
  }
}
