// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.validator.store.db.ValidatorInternalTables.ScanConfigRow
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorInternalStore
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.validator.store.ValidatorInternalStore

abstract class ValidatorInternalStoreTest
    extends AsyncWordSpec
    with Matchers
    with HasExecutionContext {

  protected def mkStore(): Future[ValidatorInternalStore]

  "ValidatorInternalStore" should {

    "getScanConfigs" should {
      "return empty sequence if no records exist" in {
        for {
          store <- mkStore()
          result <- store.getScanConfigs()(TraceContext.empty)
        } yield {
          result shouldBe Seq.empty
        }
      }
    }

    "setScanConfigs and getScanConfigs" should {

      "correctly upsert data and retrieve only rows with the highest restart_count" in {
        val tc = TraceContext.empty

        val configsR1 = Seq(
          ScanConfigRow("SV1", "url-a", 1),
          ScanConfigRow("SV2", "url-b", 1),
        )

        val configsR2 = Seq(
          ScanConfigRow("SV1", "url-a", 2),
          ScanConfigRow("SV2", "url-b", 2),
          ScanConfigRow("SV3", "url-c", 2),
        )

        val configsR3 = Seq(
          ScanConfigRow("SV1", "url-d", 3),
          ScanConfigRow("SV2", "url-a", 3),
        )

        for {
          store <- mkStore()

          _ <- store.setScanConfigs(configsR1)(tc)
          result1 <- store.getScanConfigs()(tc)

          _ <- store.setScanConfigs(configsR2)(tc)
          result2 <- store.getScanConfigs()(tc)

          _ <- store.setScanConfigs(configsR3)(tc)
          result3 <- store.getScanConfigs()(tc)

        } yield {

          result1 should contain theSameElementsAs configsR1
          result2 should contain theSameElementsAs configsR2
          result3 should contain theSameElementsAs configsR3
        }
      }

      "handle idempotent writes of the same max count correctly" in {
        val tc = TraceContext.empty

        val configs = Seq(
          ScanConfigRow("SV1", "url-a", 5),
          ScanConfigRow("SV2", "url-b", 5),
        )

        for {
          store <- mkStore()
          _ <- store.setScanConfigs(configs)(tc)
          _ <- store.setScanConfigs(configs)(tc)
          result <- store.getScanConfigs()(tc)
        } yield {
          result should contain theSameElementsAs configs
        }
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

  def resetValidatorInternalTables(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {
    import storage.api.*

    storage.update_(
      sqlu"DELETE FROM validator_scan_config",
      "reset-validator-scan-config-table",
    )
  }

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
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetValidatorInternalTables(
    storage
  )
}
