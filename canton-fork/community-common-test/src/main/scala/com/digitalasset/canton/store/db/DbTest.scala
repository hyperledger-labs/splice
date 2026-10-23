// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.store.db

import com.digitalasset.canton.config.DbConfig
import com.digitalasset.canton.lifecycle.{FlagCloseable, FutureUnlessShutdown, HasCloseContext}
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.DbStorageSetup.DbBasicConfig
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasExecutionContext}
import org.scalatest.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Base test for writing a database backed storage test. To ensure idempotency and safety under
  * retries of the store each write operation is executed twice. Each database should provide a
  * DbTest implementation that can then be mixed into a storage test to provide the actual backend.
  * See DbCryptoVaultStoreTest for example usage.
  */
trait DbTest
    extends BeforeAndAfterAll
    with BeforeAndAfterEach
    with FlagCloseable
    with HasCloseContext // not used here, but required by most tests. So extending it for convenience.
    with HasExecutionContext
    with NamedLogging {
  this: Suite =>

  /** Flag to define the migration mode for the schemas */
  def migrationMode: MigrationMode =
    if (BaseTest.testedProtocolVersion.isDev) MigrationMode.DevVersion
    else MigrationMode.Standard

  protected def mkDbConfig(basicConfig: DbBasicConfig): DbConfig

  protected def createSetup(): DbStorageSetup

  /** Hook for cleaning database before running next test. */
  protected def cleanDb(storage: DbStorage)(implicit tc: TraceContext): FutureUnlessShutdown[?]

  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Null"))
  protected var setup: DbStorageSetup = _

  /** Stores the db storage implementation. Will throw if accessed before the test has started */
  protected lazy val storage: DbStorageIdempotency = {
    val s = Option(setup).map(_.storage).getOrElse(sys.error("Test has not started"))
    new DbStorageIdempotency(s, timeouts, loggerFactory)
  }

  override def beforeAll(): Unit = TraceContext.withNewTraceContext("test") { implicit tc =>
    // Non-standard order. Setup needs to be created first, because super can be MyDbTest and therefore super.beforeAll
    // may already access setup.
    try {
      setup = createSetup().initialized()
      setup.migrateDb()
      super.beforeAll()
    } catch {
      case NonFatal(e) =>
        // Logging the error, as an exception in this method will abort the test suite with no log output.
        logger.error("beforeAll failed", e)
        throw e
    }
  }

  override def afterAll(): Unit = TraceContext.withNewTraceContext("test") { implicit tc =>
    try {
      // Non-standard order.
      // First delete test data.
      cleanup()

      // Free resources of MyDbTest, if there are any.
      close()
      super.afterAll() // This will also close the executionContext, unfortunately.

      // Release database. Fortunately, this seems not to require an executionContext.
      storage.close()
      setup.close()
    } catch {
      case NonFatal(e) =>
        // Logging the error, as an exception in this method will abort the test suite with no log output.
        logger.error("afterAll failed", e)
        throw e
    }
  }

  override def beforeEach(): Unit = TraceContext.withNewTraceContext("test") { implicit tc =>
    try {
      cleanup()
      super.beforeEach()
    } catch {
      case NonFatal(e) =>
        // Logging the error, as an exception in this method will abort the test suite with no log output.
        logger.error("beforeEach failed", e)
        throw e
    }
  }

  private def cleanup()(implicit tc: TraceContext): Unit = {
    // Use the underlying storage for clean-up operations, so we don't run clean-ups twice
    // cleanDB is usually implemented by a TRUNCATE statement, which can be very slow,
    // we therefore use a long timeout.
    Await.result(cleanDb(storage.underlying), 120.seconds)
  }
}
