package com.daml.network.sv.history

import com.daml.ledger.javaapi.data.Value
import com.daml.network.codegen.java.cn.svcrules.{
  SvcRules,
  SvcRules_CloseVoteRequest2,
  SvcRules_ExecuteDefiniteVote,
  SvcRules_VoteRequest_Expire,
  VoteRequestResult2,
  VoteResult,
}
import com.daml.network.util.ExerciseNodeCompanion

object SvcRulesExecuteDefiniteVote extends ExerciseNodeCompanion {
  override type Tpl = SvcRules
  override type Arg = SvcRules_ExecuteDefiniteVote
  override type Res = VoteResult

  override val template = SvcRules.COMPANION

  override val choice = SvcRules.CHOICE_SvcRules_ExecuteDefiniteVote

  override val argDecoder = SvcRules_ExecuteDefiniteVote.valueDecoder()
  override def argToValue(a: Arg) = a.toValue

  override val resDecoder = VoteResult.valueDecoder()
  override def resToValue(r: Res): Value = r.toValue
}

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

object SvcRulesVoteRequestExpire extends ExerciseNodeCompanion {
  override type Tpl = SvcRules
  override type Arg = SvcRules_VoteRequest_Expire
  override type Res = VoteResult

  override val template = SvcRules.COMPANION

  override val choice = SvcRules.CHOICE_SvcRules_VoteRequest_Expire

  override val argDecoder = SvcRules_VoteRequest_Expire.valueDecoder()
  override def argToValue(a: Arg) = a.toValue

  override val resDecoder = VoteResult.valueDecoder()
  override def resToValue(r: Res): Value = r.toValue
}
