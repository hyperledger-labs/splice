// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.javaapi.data.codegen.{Exercised, Update}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyInstances, PrettyPrinting}

import scala.jdk.CollectionConverters.*

/** A reference to a token standard factory choice together with
  * the disclosures required to call it.
  *
  * Use this one as the intermediate type on the Scala side when calling
  * factory choices.
  */
case class FactoryChoiceWithDisclosures[R](
    exercise: Update[Exercised[R]],
    // We are not using our own [[DisclosedContracts]] type as that one requires too
    // many parsing steps. We just want to pass this context through.
    disclosedContracts: Seq[CommandsOuterClass.DisclosedContract],
) extends PrettyPrinting {
  def commands = exercise.commands().asScala.toSeq

  override protected def pretty: Pretty[FactoryChoiceWithDisclosures.this.type] = prettyNode(
    "FactoryChoiceWithDisclosures",
    param[FactoryChoiceWithDisclosures[R], String]("exercise", _.exercise.toString)(
      PrettyInstances.prettyString
    ),
  )
}
