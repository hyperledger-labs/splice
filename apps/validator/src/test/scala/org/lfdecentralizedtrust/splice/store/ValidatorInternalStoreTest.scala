// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.validator.store.{
  ScanUrlInternalConfig,
  ValidatorConfigProvider,
  ValidatorInternalStore,
  ValidatorStore,
}
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorInternalStore
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

abstract class ValidatorInternalStoreTest extends StoreTest with Matchers with HasExecutionContext {

  protected def mkStore(name: String): Future[ValidatorInternalStore]
  protected def mkProvider(name: String): Future[ValidatorConfigProvider]
  private lazy val validator = mkPartyId(s"validator")
  lazy val storeKey = ValidatorStore.Key(
    dsoParty = dsoParty,
    validatorParty = validator,
  )

  "ValidatorInternalStore" should {

    val configKey = "key1"
    val configValue = "payload1"
    val otherKey = "key2"
    val otherValue = "payload2"

    "set and get a payload successfully" in {
      for {
        store <- mkStore("alice")
        _ <- store.setConfig(configKey, configValue)
        retrievedValue <- store.getConfig[String](configKey).value
      } yield {
        retrievedValue shouldBe Some(configValue)
      }
    }

    "return None for a non-existent key" in {
      for {
        store <- mkStore("alice")
        retrievedValue <- store.getConfig[String]("non-existent-key").value
      } yield {
        retrievedValue shouldBe None
      }
    }

    "update an existing payload" in {
      for {
        store <- mkStore("alice")
        _ <- store.setConfig(configKey, configValue)
        _ <- store.setConfig(configKey, otherValue)
        retrievedValue <- store.getConfig[String](configKey).value
      } yield {
        retrievedValue shouldBe Some(otherValue)
      }
    }

    "handle multiple different keys independently" in {
      for {
        store <- mkStore("alice")
        _ <- store.setConfig(configKey, configValue)
        _ <- store.setConfig(otherKey, otherValue)

        configKeyValue <- store.getConfig[String](configKey).value
        otherKeyValue <- store.getConfig[String](otherKey).value
      } yield {
        configKeyValue shouldBe Some(configValue)
        otherKeyValue shouldBe Some(otherValue)
      }
    }

    val scanConfig1: Seq[ScanUrlInternalConfig] = Seq(
      ScanUrlInternalConfig("sv1", "url1"),
      ScanUrlInternalConfig("sv2", "url2"),
    )

    val scanConfig2: Seq[ScanUrlInternalConfig] = Seq(
      ScanUrlInternalConfig("svA", "urlA"),
      ScanUrlInternalConfig("svB", "urlB"),
    )

    "CONFIG_PROVIDER set and retrieve a ScanUrlInternalConfig list successfully" in {
      for {
        provider <- mkProvider("alice")
        _ <- provider.setScanUrlInternalConfig(scanConfig1)
        retrievedConfigOption <- provider.getScanUrlInternalConfig.value
      } yield {
        retrievedConfigOption shouldBe Some(scanConfig1)
      }
    }

    "CONFIG_PROVIDER update an existing ScanUrlInternalConfig list" in {
      for {
        provider <- mkProvider("alice")
        _ <- provider.setScanUrlInternalConfig(scanConfig1)
        _ <- provider.setScanUrlInternalConfig(scanConfig2) // Overwrite
        retrievedConfigOption <- provider.getScanUrlInternalConfig.value
      } yield {
        retrievedConfigOption shouldBe Some(scanConfig2)
      }
    }

    "CONFIG_PROVIDER return None when no ScanUrlInternalConfig is found" in {
      for {
        provider <- mkProvider("alice")
        retrievedConfigOption <- provider.getScanUrlInternalConfig.value
      } yield {
        retrievedConfigOption shouldBe None
      }
    }

    "CONFIG_PROVIDER for two different store descriptors should not collide" in {
      for {
        provider1 <- mkProvider("alice")
        provider2 <- mkProvider("bob")
        _ <- provider1.setScanUrlInternalConfig(scanConfig1)
        _ <- provider2.setScanUrlInternalConfig(scanConfig2)
        retrievedConfigOption1 <- provider1.getScanUrlInternalConfig.value
        retrievedConfigOption2 <- provider2.getScanUrlInternalConfig.value
      } yield {
        retrievedConfigOption1 shouldBe Some(scanConfig1)
        retrievedConfigOption2 shouldBe Some(scanConfig2)
      }
    }
  }

}

class DbValidatorInternalStoreTest
    extends ValidatorInternalStoreTest
    with HasActorSystem
    with SplicePostgresTest {

  override protected def timeouts = new ProcessingTimeout

  private def buildDbStore(name: String): Future[ValidatorInternalStore] = {

    val internalStore = new DbValidatorInternalStore(
      storeKey,
      DomainMigrationInfo(
        domainMigrationId,
        None,
      ),
      mkParticipantId("ValidatorInternalStoreTest:" + name),
      storage,
      loggerFactory,
    )

    for {
      _ <- internalStore.initializeState()
    } yield internalStore
  }

  override protected def mkStore(name: String): Future[ValidatorInternalStore] =
    buildDbStore(name)

  override protected def mkProvider(name: String): Future[ValidatorConfigProvider] = {
    val internalStore = buildDbStore(name)
    internalStore.map(store => new ValidatorConfigProvider(store))
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
