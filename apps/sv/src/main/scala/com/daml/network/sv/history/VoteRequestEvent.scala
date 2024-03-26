package com.daml.network.sv.history

import com.daml.ledger.javaapi.data.Value
import com.daml.network.codegen.java.cn.dsorules.{
  DsoRules,
  DsoRules_CloseVoteRequest,
  VoteRequestResult,
}
import com.daml.network.util.ExerciseNodeCompanion

object DsoRulesCloseVoteRequest extends ExerciseNodeCompanion {
  override type Tpl = DsoRules
  override type Arg = DsoRules_CloseVoteRequest
  override type Res = VoteRequestResult

  override val template = DsoRules.COMPANION

  override val choice = DsoRules.CHOICE_DsoRules_CloseVoteRequest

  override val argDecoder = DsoRules_CloseVoteRequest.valueDecoder()
  override def argToValue(a: Arg) = a.toValue

  override val resDecoder = VoteRequestResult.valueDecoder()
  override def resToValue(r: Res): Value = r.toValue
}
