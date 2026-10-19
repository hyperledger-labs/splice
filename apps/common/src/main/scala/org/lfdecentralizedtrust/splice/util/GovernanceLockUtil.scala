// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import io.grpc.Status
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock as gl

object GovernanceLockUtil {
  def foldSpecification[A](lock: gl.GovernanceLock)(
      onFull: gl.FullLockSpecification => A,
      onVesting: gl.VestingLockSpecification => A,
  ): A =
    lock.specification match {
      case s: gl.governancelockspecification.GLS_FullLock => onFull(s.fullLockSpecificationValue)
      case s: gl.governancelockspecification.GLS_VestingLock =>
        onVesting(s.vestingLockSpecificationValue)
      case s =>
        throw Status.INTERNAL
          .withDescription(s"Unexpected GovernanceLockSpecification: $s")
          .asRuntimeException()
    }

  def getGovernanceLockKind(lock: gl.GovernanceLock): gl.GovernanceLockKind =
    foldSpecification(lock)(_.kind, _.kind)

  def getVestingLockSpecification(lock: gl.GovernanceLock): Option[gl.VestingLockSpecification] =
    foldSpecification(lock)(_ => None, Some(_))
}
