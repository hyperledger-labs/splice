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

  protected def mkStore(): Future[ValidatorInternalStore]
  protected def mkProvider(): Future[ValidatorConfigProvider]
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
        store <- mkStore()
        _ <- store.setConfig(configKey, configValue)
        retrievedValue <- store.getConfig[String](configKey).value
      } yield {
        retrievedValue shouldBe Some(configValue)
      }
    }

    "return None for a non-existent key" in {
      for {
        store <- mkStore()
        retrievedValue <- store.getConfig[String]("non-existent-key").value
      } yield {
        retrievedValue shouldBe None
      }
    }

    "update an existing payload" in {
      for {
        store <- mkStore()
        _ <- store.setConfig(configKey, configValue)
        _ <- store.setConfig(configKey, otherValue)
        retrievedValue <- store.getConfig[String](configKey).value
      } yield {
        retrievedValue shouldBe Some(otherValue)
      }
    }

    "handle multiple different keys independently" in {
      for {
        store <- mkStore()
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
        provider <- mkProvider()
        _ <- provider.setScanUrlInternalConfig(scanConfig1)
        retrievedConfigOption <- provider.getScanUrlInternalConfig.value
      } yield {
        retrievedConfigOption shouldBe Some(scanConfig1)
      }
    }

    "CONFIG_PROVIDER update an existing ScanUrlInternalConfig list" in {
      for {
        provider <- mkProvider()
        _ <- provider.setScanUrlInternalConfig(scanConfig1)
        _ <- provider.setScanUrlInternalConfig(scanConfig2) // Overwrite
        retrievedConfigOption <- provider.getScanUrlInternalConfig.value
      } yield {
        retrievedConfigOption shouldBe Some(scanConfig2)
      }
    }

    "CONFIG_PROVIDER return None when no ScanUrlInternalConfig is found" in {
      for {
        provider <- mkProvider()
        retrievedConfigOption <- provider.getScanUrlInternalConfig.value
      } yield {
        retrievedConfigOption shouldBe None
      }
    }
  }

}

class DbValidatorInternalStoreTest
    extends ValidatorInternalStoreTest
    with HasActorSystem
    with SplicePostgresTest {

  override protected def timeouts = new ProcessingTimeout

  private def buildDbStore(): Future[ValidatorInternalStore] = {

    val internalStore = new DbValidatorInternalStore(
      storeKey,
      DomainMigrationInfo(
        domainMigrationId,
        None,
      ),
      mkParticipantId("ValidatorInternalStoreTest"),
      storage,
      loggerFactory,
    )

    for {
      _ <- internalStore.initializeState()
    } yield internalStore
  }

  override protected def mkStore(): Future[ValidatorInternalStore] =
    buildDbStore()

  override protected def mkProvider(): Future[ValidatorConfigProvider] = {
    val internalStore = buildDbStore()
    internalStore.map(store => new ValidatorConfigProvider(store))
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
