// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import io.grpc.Status
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock as gl

object GovernanceLockUtil {
  def foldSpecification[A](specification: gl.GovernanceLockSpecification)(
      onFull: gl.FullLockSpecification => A,
      onVesting: gl.VestingLockSpecification => A,
  ): A =
    specification match {
      case s: gl.governancelockspecification.GLS_FullLock => onFull(s.fullLockSpecificationValue)
      case s: gl.governancelockspecification.GLS_VestingLock =>
        onVesting(s.vestingLockSpecificationValue)
      case s =>
        throw Status.INTERNAL
          .withDescription(s"Unexpected GovernanceLockSpecification: $s")
          .asRuntimeException()
    }

  def foldSpecification[A](lock: gl.GovernanceLock)(
      onFull: gl.FullLockSpecification => A,
      onVesting: gl.VestingLockSpecification => A,
  ): A =
    foldSpecification(lock.specification)(onFull, onVesting)

  def getGovernanceLockKind(lock: gl.GovernanceLock): gl.GovernanceLockKind =
    foldSpecification(lock)(_.kind, _.kind)

  def getVestingLockSpecification(lock: gl.GovernanceLock): Option[gl.VestingLockSpecification] =
    foldSpecification(lock)(_ => None, Some(_))

  /** Encodes a lock as the `governance_lock_type` and `governance_lock_subject` index columns. */
  def governanceLockTypeAndSubject(lock: gl.GovernanceLock): (String, String) =
    governanceLockTypeAndSubject(lock.specification)

  /** Encodes a lock specification as the `governance_lock_type` and
    * `governance_lock_subject` index columns.
    */
  def governanceLockTypeAndSubject(
      specification: gl.GovernanceLockSpecification
  ): (String, String) = {
    val lockType = foldSpecification(specification)(_ => "f", _ => "v")
    val (kindTag, subject) = foldSpecification(specification)(_.kind, _.kind) match {
      case kind: gl.governancelockkind.GLK_FeaturedApp => ("fa", kind.provider)
      case kind: gl.governancelockkind.GLK_ProvisionalFeaturedApp => ("pfa", kind.provider)
      case kind: gl.governancelockkind.GLK_SuperValidatorRightsOwner => ("sv", kind.svName)
      case kind =>
        throw Status.INTERNAL
          .withDescription(s"Unexpected GovernanceLockKind: $kind")
          .asRuntimeException()
    }
    (s"$lockType:$kindTag", subject)
  }
}
