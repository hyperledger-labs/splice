// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.javaapi.data.Command

/** A reference to a token standard factory choice together with
  * the disclosures required to call it.
  *
  * Use this one as the intermediate type on the Scala side when calling
  * factory choices.
  */
case class FactoryChoiceWithDisclosures(
    commands: Seq[Command],
    // We are not using our own [[DisclosedContracts]] type as that one requires too
    // many parsing steps. We just want to pass this context through.
    disclosedContracts: Seq[CommandsOuterClass.DisclosedContract],
)
