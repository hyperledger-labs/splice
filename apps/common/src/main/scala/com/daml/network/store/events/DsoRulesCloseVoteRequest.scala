// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store.events

import com.daml.network.codegen.java.splice.dsorules.DsoRules
import com.daml.network.util.ExerciseNodeCompanion

object DsoRulesCloseVoteRequest
    extends ExerciseNodeCompanion.Mk(
      template = DsoRules.COMPANION,
      choice = DsoRules.CHOICE_DsoRules_CloseVoteRequest,
    )
