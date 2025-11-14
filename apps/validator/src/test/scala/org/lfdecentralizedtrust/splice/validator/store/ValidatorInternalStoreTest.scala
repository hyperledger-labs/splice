// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem}
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.validator.store.ValidatorConfigProvider.ScanUrlInternalConfig
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorInternalStore
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.Future

abstract class ValidatorInternalStoreTest extends AsyncWordSpec with BaseTest {

  protected def mkStore(): Future[ValidatorInternalStore]
  protected def mkProvider(): Future[ValidatorConfigProvider]

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
        retrievedValue.value.value shouldBe configValue
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
        retrievedValue.value.value shouldBe otherValue
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
        configKeyValue.value.value shouldBe configValue
        otherKeyValue.value.value shouldBe otherValue
      }
    }

    "delete single key" in {
      for {
        store <- mkStore()
        _ <- store.setConfig(configKey, configValue)
        _ <- store.setConfig(otherKey, otherValue)

        _ <- store.deleteConfig(configKey)
        configKeyValue <- store.getConfig[String](configKey).value
        otherKeyValue <- store.getConfig[String](otherKey).value
      } yield {
        configKeyValue shouldBe None
        otherKeyValue.value.value shouldBe otherValue
      }
    }
  }

  "Validator config provider" should {

    "handle scan urls" should {

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
          retrievedConfigOption <- provider.getScanUrlInternalConfig().value
        } yield {
          retrievedConfigOption shouldBe Some(scanConfig1)
        }
      }

      "CONFIG_PROVIDER update an existing ScanUrlInternalConfig list" in {
        for {
          provider <- mkProvider()
          _ <- provider.setScanUrlInternalConfig(scanConfig1)
          _ <- provider.setScanUrlInternalConfig(scanConfig2) // Overwrite
          retrievedConfigOption <- provider.getScanUrlInternalConfig().value
        } yield {
          retrievedConfigOption shouldBe Some(scanConfig2)
        }
      }

      "CONFIG_PROVIDER return None when no ScanUrlInternalConfig is found" in {
        for {
          provider <- mkProvider()
          retrievedConfigOption <- provider.getScanUrlInternalConfig().value
        } yield {
          retrievedConfigOption shouldBe None
        }
      }
    }

    "handle migrating parties" should {

      "handle set delete update" in {
        for {
          provider <- mkProvider()
          parties = Set(
            PartyId.tryFromProtoPrimitive("party::test")
          )
          _ <- provider.setPartiesToMigrate(parties)
          partiesRead <- provider.getPartiesToMigrate().value
          _ <- provider.clearPartiesToMigrate()
          partiesReadAfterClear <- provider.getPartiesToMigrate().value
        } yield {
          partiesRead shouldBe Some(parties)
          partiesReadAfterClear shouldBe None
        }

      }

    }

  }

}

class DbValidatorInternalStoreTest
    extends ValidatorInternalStoreTest
    with HasActorSystem
    with SplicePostgresTest {

  private def buildDbStore(): ValidatorInternalStore = {
    new DbValidatorInternalStore(
      storage = storage,
      loggerFactory = loggerFactory,
    )
  }

  override protected def mkStore(): Future[ValidatorInternalStore] = Future.successful {
    buildDbStore()
  }

  override protected def mkProvider(): Future[ValidatorConfigProvider] = Future.successful {
    val internalStore = buildDbStore()
    new ValidatorConfigProvider(internalStore, loggerFactory)
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
