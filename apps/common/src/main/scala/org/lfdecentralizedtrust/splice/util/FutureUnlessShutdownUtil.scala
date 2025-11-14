// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.data.OptionT
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object FutureUnlessShutdownUtil {

  implicit def futureUnlessShutdownToFuture[A](
      f: FutureUnlessShutdown[A]
  )(implicit ec: ExecutionContext): Future[A] = {
    f.failOnShutdownToAbortException("Splice unsafe shutdown future")
  }

  implicit def optionTfutureUnlessShutdownToFuture[A](
      f: OptionT[FutureUnlessShutdown, A]
  )(implicit ec: ExecutionContext): OptionT[Future, A] = {
    OptionT(futureUnlessShutdownToFuture(f.value))
  }

  implicit class FutureUnlessShutdownOps[A](val f: FutureUnlessShutdown[A]) extends AnyVal {
    def toFuture(implicit ec: ExecutionContext): Future[A] =
      f.failOnShutdownToAbortException("Splice unsafe shutdown future")
  }

}
