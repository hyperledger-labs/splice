package com.daml.network.sv.history

import com.daml.network.codegen.java.splice.dsorules.DsoRules
import com.daml.network.util.ExerciseNodeCompanion

object DsoRulesCloseVoteRequest
    extends ExerciseNodeCompanion.Mk(
      template = DsoRules.COMPANION,
      choice = DsoRules.CHOICE_DsoRules_CloseVoteRequest,
    )
