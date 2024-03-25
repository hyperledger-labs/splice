package com.daml.network.store.events

import com.daml.network.codegen.java.cc.coin
import com.daml.network.util.ExerciseNodeCompanion

object SvRewardCoupon_ArchiveAsBeneficiary extends ExerciseNodeCompanion {
  override type Tpl = coin.SvRewardCoupon
  override type Arg = coin.SvRewardCoupon_ArchiveAsBeneficiary
  override type Res = coin.SvRewardCoupon_ArchiveAsBeneficiaryResult

  override val template = coin.SvRewardCoupon.COMPANION
  override val choice = coin.SvRewardCoupon.CHOICE_SvRewardCoupon_ArchiveAsBeneficiary

  override val argDecoder = coin.SvRewardCoupon_ArchiveAsBeneficiary.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = coin.SvRewardCoupon_ArchiveAsBeneficiaryResult.valueDecoder
  override def resToValue(res: Res) = res.toValue
}
