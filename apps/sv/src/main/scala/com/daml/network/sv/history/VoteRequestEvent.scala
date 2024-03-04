package com.daml.network.sv.history

import com.daml.ledger.javaapi.data.Value
import com.daml.network.codegen.java.cn.svcrules.{
  SvcRules,
  SvcRules_CloseVoteRequest,
  VoteRequestResult,
}
import com.daml.network.util.ExerciseNodeCompanion

object SvcRulesCloseVoteRequest extends ExerciseNodeCompanion {
  override type Tpl = SvcRules
  override type Arg = SvcRules_CloseVoteRequest
  override type Res = VoteRequestResult

  override val template = SvcRules.COMPANION

  override val choice = SvcRules.CHOICE_SvcRules_CloseVoteRequest

  override val argDecoder = SvcRules_CloseVoteRequest.valueDecoder()
  override def argToValue(a: Arg) = a.toValue

  override val resDecoder = VoteRequestResult.valueDecoder()
  override def resToValue(r: Res): Value = r.toValue
}
