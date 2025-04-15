// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.api.v2.CommandsOuterClass
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1

/** An enriched version of a ChoiceContext retrieved from OpenAPI calls.
  *
  * Use this one as the intermediate type on the Scala side when calling
  * choices that require fetching a choice context.
  */
case class ChoiceContextWithDisclosures(
    // We are not using our own [[DisclosedContracts]] type as that one requires too
    // many parsing steps. We just want to pass this context through.
    disclosedContracts: Seq[CommandsOuterClass.DisclosedContract],
    choiceContext: metadatav1.ChoiceContext,
) {
  import org.lfdecentralizedtrust.splice.util.ChoiceContextWithDisclosures.*

  def toExtraArgs(meta: metadatav1.Metadata = emptyMetadata): metadatav1.ExtraArgs =
    new metadatav1.ExtraArgs(choiceContext, meta)
}

object ChoiceContextWithDisclosures {
  val emptyMetadata: metadatav1.Metadata = new metadatav1.Metadata(java.util.Map.of())
  val emptyExtraArgs: metadatav1.ExtraArgs = new metadatav1.ExtraArgs(
    new metadatav1.ChoiceContext(
      java.util.Map.of()
    ),
    emptyMetadata,
  )
}
