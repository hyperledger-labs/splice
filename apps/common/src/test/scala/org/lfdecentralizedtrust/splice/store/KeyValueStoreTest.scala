package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.store.db.{SplicePostgresTest, StoreDescriptor}
import org.scalatest.matchers.should.Matchers

class KeyValueStoreTest
    extends StoreTest
    with Matchers
    with HasExecutionContext
    with SplicePostgresTest {
  private val storeDescriptor =
    StoreDescriptor(
      version = 1,
      name = "DbMultiDomainAcsStoreTest",
      party = dsoParty,
      participant = mkParticipantId("participant"),
      key = Map(),
    )

  private def mkStore() = KeyValueStore(
    storeDescriptor,
    KeyValueStoreDbTableConfig("validator_internal_config", "config_key", "config_value"),
    storage,
    loggerFactory,
  )

  "KeyValueStore" should {

    val configKey = "key1"
    val configValue = "jsonPayload1"
    val otherKey = "key2"
    val otherValue = "jsonPayload2"

    "set and get a payload successfully" in {
      for {
        store <- mkStore()
        _ <- store.setValue(configKey, configValue)
        retrievedValue <- store.getValue[String](configKey).value
      } yield {
        retrievedValue.value.value shouldBe configValue
      }
    }

    "return None for a non-existent key" in {
      for {
        store <- mkStore()
        retrievedValue <- store.getValue[String]("non-existent-key").value
      } yield {
        retrievedValue shouldBe None
      }
    }

    "update an existing payload" in {
      for {
        store <- mkStore()
        _ <- store.setValue(configKey, configValue)
        _ <- store.setValue(configKey, otherValue)
        retrievedValue <- store.getValue[String](configKey).value
      } yield {
        retrievedValue.value.value shouldBe otherValue
      }
    }

    "handle multiple different keys independently" in {
      for {
        store <- mkStore()
        _ <- store.setValue(configKey, configValue)
        _ <- store.setValue(otherKey, otherValue)

        configKeyValue <- store.getValue[String](configKey).value
        otherKeyValue <- store.getValue[String](otherKey).value
      } yield {
        configKeyValue.value.value shouldBe configValue
        otherKeyValue.value.value shouldBe otherValue
      }
    }

    "delete single key" in {
      for {
        store <- mkStore()
        _ <- store.setValue(configKey, configValue)
        _ <- store.setValue(otherKey, otherValue)

        _ <- store.deleteKey(configKey)
        configKeyValue <- store.getValue[String](configKey).value
        otherKeyValue <- store.getValue[String](otherKey).value
      } yield {
        configKeyValue shouldBe None
        otherKeyValue.value.value shouldBe otherValue
      }
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}
