// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.events

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet
import org.lfdecentralizedtrust.splice.util.ExerciseNodeCompanion

object SvRewardCoupon_ArchiveAsBeneficiary
    extends ExerciseNodeCompanion.Mk(
      template = amulet.SvRewardCoupon.COMPANION,
      choice = amulet.SvRewardCoupon.CHOICE_SvRewardCoupon_ArchiveAsBeneficiary,
    )
