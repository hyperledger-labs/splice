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
import org.lfdecentralizedtrust.splice.validator.store.ValidatorInternalStore
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
    val initialValues = Json.fromString("payload1")
    val otherKey = "other-config"
    val otherValues = Json.fromString("payload2")

    "set and get a configuration successfully" in {
      for {
        store <- mkStore()
        _ <- store.setConfig(configKey, initialValues)
        retrievedValues <- store.getConfig(configKey)
      } yield {
        retrievedValues shouldEqual initialValues
      }
    }

    "return an empty JSON for a non-existent key" in {
      for {
        store <- mkStore()
        retrievedValues <- store.getConfig("non-existent-key")
      } yield {
        retrievedValues shouldEqual Json.obj()
      }
    }

    "update an existing configuration on conflict" in {
      for {
        store <- mkStore()
        _ <- store.setConfig(configKey, initialValues)
        _ <- store.setConfig(configKey, otherValues)
        retrievedValues <- store.getConfig(configKey)
      } yield {
        retrievedValues shouldEqual otherValues
      }
    }

    "handle multiple different keys independently" in {
      for {
        store <- mkStore()
        _ <- store.setConfig(configKey, initialValues)
        _ <- store.setConfig(otherKey, otherValues)

        mainKeyValues <- store.getConfig(configKey)
        otherKeyValues <- store.getConfig(otherKey)
      } yield {
        mainKeyValues shouldEqual initialValues
        otherKeyValues shouldEqual otherValues
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
