// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.store.{KeyValueStore, StoreTest}
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.validator.store.ValidatorConfigProvider.ScanUrlInternalConfig
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

abstract class ValidatorConfigProviderTest
    extends StoreTest
    with Matchers
    with HasExecutionContext {

  protected def mkStore(name: String): Future[KeyValueStore]

  protected def mkProvider(name: String): Future[ValidatorConfigProvider]

  "ValidatorConfigProvider should" {

    val scanConfig1: Seq[ScanUrlInternalConfig] = Seq(
      ScanUrlInternalConfig("sv1", "url1"),
      ScanUrlInternalConfig("sv2", "url2"),
    )

    val scanConfig2: Seq[ScanUrlInternalConfig] = Seq(
      ScanUrlInternalConfig("svA", "urlA"),
      ScanUrlInternalConfig("svB", "urlB"),
    )

    "set and retrieve a ScanUrlInternalConfig list successfully" in {
      for {
        provider <- mkProvider("alice")
        _ <- provider.setScanUrlInternalConfig(scanConfig1)
        retrievedConfigOption <- provider.getScanUrlInternalConfig().value
      } yield {
        retrievedConfigOption shouldBe Some(scanConfig1)
      }
    }

    "update an existing ScanUrlInternalConfig list" in {
      for {
        provider <- mkProvider("alice")
        _ <- provider.setScanUrlInternalConfig(scanConfig1)
        _ <- provider.setScanUrlInternalConfig(scanConfig2) // Overwrite
        retrievedConfigOption <- provider.getScanUrlInternalConfig().value
      } yield {
        retrievedConfigOption shouldBe Some(scanConfig2)
      }
    }

    "return None when no ScanUrlInternalConfig is found" in {
      for {
        provider <- mkProvider("alice")
        retrievedConfigOption <- provider.getScanUrlInternalConfig().value
      } yield {
        retrievedConfigOption shouldBe None
      }
    }

    "for two different store descriptors should not collide" in {
      for {
        provider1 <- mkProvider("alice")
        provider2 <- mkProvider("bob")
        _ <- provider1.setScanUrlInternalConfig(scanConfig1)
        _ <- provider2.setScanUrlInternalConfig(scanConfig2)
        retrievedConfigOption1 <- provider1.getScanUrlInternalConfig().value
        retrievedConfigOption2 <- provider2.getScanUrlInternalConfig().value
      } yield {
        retrievedConfigOption1 shouldBe Some(scanConfig1)
        retrievedConfigOption2 shouldBe Some(scanConfig2)
      }
    }
  }

  "handle migrating parties" should {

    "handle set delete update" in {
      for {
        provider <- mkProvider("alice")
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

class DbValidatorConfigProviderTest
    extends ValidatorConfigProviderTest
    with HasActorSystem
    with SplicePostgresTest {

  override protected def timeouts = new ProcessingTimeout

  private def buildDbStore(name: String): Future[KeyValueStore] = {

    ValidatorInternalStore(
      mkParticipantId("ValidatorInternalStoreTest"),
      validatorParty = mkPartyId(name),
      storage,
      loggerFactory,
    )
  }

  override protected def mkStore(name: String): Future[KeyValueStore] =
    buildDbStore(name)

  override protected def mkProvider(name: String): Future[ValidatorConfigProvider] = {
    val internalStore = buildDbStore(name)
    internalStore.map(store => new ValidatorConfigProvider(store, loggerFactory))
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
