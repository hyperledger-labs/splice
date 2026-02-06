// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.store.db.{SplicePostgresTest, StoreDescriptor}
import org.scalatest.matchers.should.Matchers

class KeyValueStoreTest
    extends StoreTestBase
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
  private val storeDescriptor2 =
    StoreDescriptor(
      version = 2,
      name = "DbMultiDomainAcsStoreTest",
      party = dsoParty,
      participant = mkParticipantId("participant"),
      key = Map(),
    )

  private def mkStore(descriptor: StoreDescriptor) = KeyValueStore(
    descriptor,
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
        store <- mkStore(storeDescriptor)
        _ <- store.setValue(configKey, configValue)
        retrievedValue <- store.getValue[String](configKey).value
      } yield {
        retrievedValue.value.value shouldBe configValue
      }
    }

    "return None for a non-existent key" in {
      for {
        store <- mkStore(storeDescriptor)
        retrievedValue <- store.getValue[String]("non-existent-key").value
      } yield {
        retrievedValue shouldBe None
      }
    }

    "update an existing payload" in {
      for {
        store <- mkStore(storeDescriptor)
        _ <- store.setValue(configKey, configValue)
        _ <- store.setValue(configKey, otherValue)
        retrievedValue <- store.getValue[String](configKey).value
      } yield {
        retrievedValue.value.value shouldBe otherValue
      }
    }

    "handle multiple different keys independently" in {
      for {
        store <- mkStore(storeDescriptor)
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
        store <- mkStore(storeDescriptor)
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

    "different storeDescriptors should not interfere" in {
      for {
        store1 <- mkStore(storeDescriptor)
        store2 <- mkStore(storeDescriptor2)
        _ <- store1.setValue("key", "value1")
        _ <- store2.setValue("key", "value2")
        _ <- store2.setValue("key2", "something")

        k1s1 <- store1.getValue[String]("key").value
        k2s1 <- store1.getValue[String]("key2").value

        k1s2 <- store2.getValue[String]("key").value
        k2s2 <- store2.getValue[String]("key2").value
      } yield {
        k1s1.value.value shouldBe "value1"
        k2s1 shouldBe None
        k1s2.value.value shouldBe "value2"
        k2s2.value.value shouldBe "something"
      }
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}
