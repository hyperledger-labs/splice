// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.events

import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.util.ExerciseNodeCompanion

object DsoRulesCloseVoteRequest
    extends ExerciseNodeCompanion.Mk(
      template = DsoRules.COMPANION,
      choice = DsoRules.CHOICE_DsoRules_CloseVoteRequest,
    )
