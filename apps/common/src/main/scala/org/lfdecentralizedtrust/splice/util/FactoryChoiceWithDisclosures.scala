// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import PrettyInstances.*

/** A reference to a token standard factory containing:
  * - The arguments (A) to call the expected choice
  * - The disclosures required to call it.
  *
  * Use this one as the intermediate type on the Scala side when calling
  * factory choices.
  *
  * This class is compared via equality by BftScanConnection, so arguments must have a proper `equals` method defined.
  */
case class FactoryChoiceWithDisclosures[CId <: ContractId[?], A <: DamlRecord[?]](
    factoryId: CId,
    args: A,
    // We are not using our own [[DisclosedContracts]] type as that one requires too
    // many parsing steps. We just want to pass this context through.
    disclosedContracts: Seq[CommandsOuterClass.DisclosedContract],
) extends PrettyPrinting {

  override protected def pretty: Pretty[FactoryChoiceWithDisclosures.this.type] = prettyNode(
    "FactoryChoiceWithDisclosures",
    param("args", _.args),
  )
}
