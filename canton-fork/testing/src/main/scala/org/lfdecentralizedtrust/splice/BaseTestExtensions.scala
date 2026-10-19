// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.logging.{SuppressingLogger, SuppressionRule}
import org.scalatest.exceptions.TestFailedException
import org.slf4j.event.Level

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Splice additions to [[BaseTest]]. */
trait BaseTestExtensions { this: BaseTest =>

  /** Suppressed failed clue messages to make our log-checker happy when using clues within an eventually. */
  def suppressFailedClues[T](loggerFactory: SuppressingLogger)(expr: => T): T =
    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.ERROR))(
      expr,
      logEntries =>
        forAll(logEntries)(logEntry => logEntry.message should startWith("Failed: clue")),
    )

  /** Keeps evaluating `testCode` until it succeeds or a timeout occurs.
    */
  def eventuallySucceeds[T](
      timeUntilSuccess: FiniteDuration = BaseTest.DefaultEventuallyTimeUntilSuccess,
      maxPollInterval: FiniteDuration = 5.seconds,
      suppressErrors: Boolean = true,
  )(testCode: => T): T = {
    eventually(timeUntilSuccess, maxPollInterval) {
      try {
        if (suppressErrors) loggerFactory.suppressErrors(testCode) else testCode
      } catch {
        case e: TestFailedException => throw e
        case NonFatal(e) => fail(e)
      }
    }
  }
}
