package com.daml.network.store.events

import com.daml.network.codegen.java.splice.amulet
import com.daml.network.util.ExerciseNodeCompanion

object SvRewardCoupon_ArchiveAsBeneficiary extends ExerciseNodeCompanion {
  override type Tpl = amulet.SvRewardCoupon
  override type Arg = amulet.SvRewardCoupon_ArchiveAsBeneficiary
  override type Res = amulet.SvRewardCoupon_ArchiveAsBeneficiaryResult

  override val template = amulet.SvRewardCoupon.COMPANION
  override val choice = amulet.SvRewardCoupon.CHOICE_SvRewardCoupon_ArchiveAsBeneficiary

  override val argDecoder = amulet.SvRewardCoupon_ArchiveAsBeneficiary.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = amulet.SvRewardCoupon_ArchiveAsBeneficiaryResult.valueDecoder
  override def resToValue(res: Res) = res.toValue
}
