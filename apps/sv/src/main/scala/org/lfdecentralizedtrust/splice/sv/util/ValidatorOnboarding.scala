// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import org.lfdecentralizedtrust.splice.codegen.java.splice.validatoronboarding as vo
import org.lfdecentralizedtrust.splice.util.Contract

final case class ValidatorOnboarding(
    encodedSecret: String,
    contract: Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding],
)
