// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import io.circe.Json
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.validator.store.{
  ScanUrlInternalConfig,
  ValidatorInternalStore,
}
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorInternalStore
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.Future

abstract class ValidatorInternalStoreTest
    extends AsyncWordSpec
    with Matchers
    with HasExecutionContext {

  protected def mkStore(): Future[ValidatorInternalStore]

  "ValidatorInternalStore" should {
    implicit val tc: TraceContext = TraceContext.empty

    val configKey = "test-config-key"
    val initialValue = Json.fromString("payload1")
    val otherKey = "other-config"
    val otherValue = Json.fromString("payload2")

    "set and get a json payload successfully" in {
      for {
        store <- mkStore()
        _ <- store.setConfig(configKey, initialValue)
        retrievedValue <- store.getConfig(configKey)
      } yield {
        retrievedValue shouldEqual initialValue
      }
    }

    "return an empty JSON for a non-existent key" in {
      for {
        store <- mkStore()
        retrievedValue <- store.getConfig("non-existent-key")
      } yield {
        retrievedValue shouldEqual Json.obj()
      }
    }

    "update an existing payload" in {
      for {
        store <- mkStore()
        _ <- store.setConfig(configKey, initialValue)
        _ <- store.setConfig(configKey, otherValue)
        retrievedValue <- store.getConfig(configKey)
      } yield {
        retrievedValue shouldEqual otherValue
      }
    }

    "handle multiple different keys independently" in {
      for {
        store <- mkStore()
        _ <- store.setConfig(configKey, initialValue)
        _ <- store.setConfig(otherKey, otherValue)

        mainKeyValue <- store.getConfig(configKey)
        otherKeyValue <- store.getConfig(otherKey)
      } yield {
        mainKeyValue shouldEqual initialValue
        otherKeyValue shouldEqual otherValue
      }
    }

    val scanConfig1: Seq[ScanUrlInternalConfig] = Seq(
      ScanUrlInternalConfig("sv1", "url1"),
      ScanUrlInternalConfig("sv2", "url2"),
      ScanUrlInternalConfig("sv3", "url3"),
      ScanUrlInternalConfig("sv4", "url4"),
    )

    val scanConfig2: Seq[ScanUrlInternalConfig] = Seq(
      ScanUrlInternalConfig("sv1", "url5"),
      ScanUrlInternalConfig("sv2", "url6"),
      ScanUrlInternalConfig("sv3", "url7"),
      ScanUrlInternalConfig("sv4", "url8"),
    )

    "saves a scan config" in {
      for {
        store <- mkStore()
        _ <- store.setScanUrlInternalConfig(scanConfig1)
        returnConfig <- store.getScanUrlInternalConfig()
      } yield {
        returnConfig shouldEqual scanConfig1

      }
    }
    "updates a scan config" in {
      for {
        store <- mkStore()
        _ <- store.setScanUrlInternalConfig(scanConfig1)
        _ <- store.setScanUrlInternalConfig(scanConfig2)
        returnConfig <- store.getScanUrlInternalConfig()
      } yield {
        returnConfig shouldEqual scanConfig2

      }
    }

    "retrieves an empty scan config" in {
      for {
        store <- mkStore()
        returnConfig <- store.getScanUrlInternalConfig()
      } yield {
        returnConfig shouldEqual Seq.empty[ScanUrlInternalConfig]

      }
    }

  }
}

class DbValidatorInternalStoreTest
    extends ValidatorInternalStoreTest
    with HasActorSystem
    with SplicePostgresTest {

  override protected def timeouts = new ProcessingTimeout
  override protected def loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

  override protected def mkStore(): Future[ValidatorInternalStore] = Future.successful {
    val elc = ErrorLoggingContext(
      loggerFactory.getTracedLogger(getClass),
      loggerFactory.properties,
      TraceContext.empty,
    )
    implicit val cc = closeContext

    new DbValidatorInternalStore(
      storage = storage,
      loggingContext = elc,
      closeContext = cc,
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)

}
