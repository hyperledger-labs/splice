package com.daml.network.sv.history

import com.daml.ledger.javaapi.data.Value
import com.daml.network.codegen.java.splice.dsorules.{
  DsoRules,
  DsoRules_CloseVoteRequest,
  DsoRules_CloseVoteRequestResult,
}
import com.daml.network.util.ExerciseNodeCompanion

object DsoRulesCloseVoteRequest extends ExerciseNodeCompanion {
  override type Tpl = DsoRules
  override type Arg = DsoRules_CloseVoteRequest
  override type Res = DsoRules_CloseVoteRequestResult

  override val template = DsoRules.COMPANION

  override val choice = DsoRules.CHOICE_DsoRules_CloseVoteRequest

  override val argDecoder = DsoRules_CloseVoteRequest.valueDecoder()
  override def argToValue(a: Arg) = a.toValue

  override val resDecoder = DsoRules_CloseVoteRequestResult.valueDecoder()
  override def resToValue(r: Res): Value = r.toValue
}
