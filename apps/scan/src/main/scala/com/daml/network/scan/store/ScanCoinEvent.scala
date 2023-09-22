package com.daml.network.scan.store

import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn.svcrules as svcCodegen
import com.daml.network.util.ExerciseNodeCompanion

object SvcRules_CollectSvReward extends ExerciseNodeCompanion {
  override type Tpl = svcCodegen.SvcRules
  override type Arg = svcCodegen.SvcRules_CollectSvReward
  override type Res = v1.coin.CoinCreateSummary[cc.coin.Coin.ContractId]

  override val templateOrInterface = Left(svcCodegen.SvcRules.COMPANION)
  override val choice = svcCodegen.SvcRules.CHOICE_SvcRules_CollectSvReward

  override val argDecoder = svcCodegen.SvcRules_CollectSvReward.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    v1.coin.CoinCreateSummary.valueDecoder(cid =>
      new cc.coin.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}
