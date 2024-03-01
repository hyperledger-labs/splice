package com.daml.network.sv.history

import com.daml.ledger.javaapi.data.Value
import com.daml.network.codegen.java.cn.svcrules.{
  SvcRules,
  SvcRules_CloseVoteRequest2,
  VoteRequestResult2,
}
import com.daml.network.util.ExerciseNodeCompanion

object SvcRulesCloseVoteRequest2 extends ExerciseNodeCompanion {
  override type Tpl = SvcRules
  override type Arg = SvcRules_CloseVoteRequest2
  override type Res = VoteRequestResult2

  override val template = SvcRules.COMPANION

  override val choice = SvcRules.CHOICE_SvcRules_CloseVoteRequest2

  override val argDecoder = SvcRules_CloseVoteRequest2.valueDecoder()
  override def argToValue(a: Arg) = a.toValue

  override val resDecoder = VoteRequestResult2.valueDecoder()
  override def resToValue(r: Res): Value = r.toValue
}
