package com.daml.network.store.events

import com.daml.network.codegen.java.splice.amulet
import com.daml.network.util.ExerciseNodeCompanion

object SvRewardCoupon_ArchiveAsBeneficiary
    extends ExerciseNodeCompanion.Mk(
      template = amulet.SvRewardCoupon.COMPANION,
      choice = amulet.SvRewardCoupon.CHOICE_SvRewardCoupon_ArchiveAsBeneficiary,
    )
