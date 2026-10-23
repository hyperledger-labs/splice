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

  sealed abstract class StoreType(val type_ : String)
  object StoreType {
    case object Full extends StoreType("f")
    case object Vesting extends StoreType("v")
  }

  sealed abstract class StoreKind(val kind: String)
  object StoreKind {
    case object FeaturedApp extends StoreKind("fa")
    case object ProvisionalFeaturedApp extends StoreKind("pfa")
    case object SuperValidator extends StoreKind("sv")
  }

  final case class StoreTypeAndKind(type_ : StoreType, kind: StoreKind) {
    final val storeValue: String = s"${type_.type_}:${kind.kind}"
  }
  object StoreTypeAndKind {
    val provisionalFeaturedApp = StoreTypeAndKind(StoreType.Full, StoreKind.ProvisionalFeaturedApp)
  }

  /** Encodes a lock as the `governance_lock_type` and `governance_lock_subject` index columns. */
  def governanceLockTypeAndSubject(lock: gl.GovernanceLock): (StoreTypeAndKind, String) = {
    val storeType =
      foldSpecification[StoreType](lock)(_ => StoreType.Full, _ => StoreType.Vesting)
    val (storeKind, subject) = getGovernanceLockKind(lock) match {
      case kind: gl.governancelockkind.GLK_FeaturedApp =>
        // The type annotation is necessary to avoid a wartremover error that says
        // Inferred type containing Product
        (StoreKind.FeaturedApp: StoreKind, kind.provider)
      case kind: gl.governancelockkind.GLK_ProvisionalFeaturedApp =>
        (StoreKind.ProvisionalFeaturedApp, kind.provider)
      case kind: gl.governancelockkind.GLK_SuperValidatorRightsOwner =>
        (StoreKind.SuperValidator, kind.svName)
      case kind =>
        throw Status.INTERNAL
          .withDescription(s"Unexpected GovernanceLockKind: $kind")
          .asRuntimeException()
    }
    (StoreTypeAndKind(storeType, storeKind), subject)
  }
}
