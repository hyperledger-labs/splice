package com.daml.network.sv.history

import com.daml.ledger.javaapi.data.Value
import com.daml.network.codegen.java.cn.svcrules.{
  SvcRules,
  SvcRules_ExecuteDefiniteVote,
  VoteResult,
}
import com.daml.network.util.ExerciseNodeCompanion

object SvcRulesExecuteDefiniteVote extends ExerciseNodeCompanion {
  override type Tpl = SvcRules
  override type Arg = SvcRules_ExecuteDefiniteVote
  override type Res = VoteResult

  override val templateOrInterface = Left(SvcRules.COMPANION)

  override val choice = SvcRules.CHOICE_SvcRules_ExecuteDefiniteVote

  override val argDecoder = SvcRules_ExecuteDefiniteVote.valueDecoder()
  override def argToValue(a: Arg) = a.toValue

  override val resDecoder = VoteResult.valueDecoder()
  override def resToValue(r: Res): Value = r.toValue
}
