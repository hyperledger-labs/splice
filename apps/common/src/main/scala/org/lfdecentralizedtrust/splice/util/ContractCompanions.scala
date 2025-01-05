// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.{Choice, ContractId, DamlRecord}
import org.lfdecentralizedtrust.splice.codegen.java.{
  DecoderSpliceAmulet,
  DecoderSpliceWallet,
  DecoderSpliceWalletPayments,
  DecoderSpliceDsoGovernance,
  DecoderSpliceAmuletNameService,
  DecoderSpliceValidatorLifecycle,
}

import scala.jdk.CollectionConverters.*

object ContractCompanions {
  type C = Contract.Companion.Template[_ <: ContractId[?], _ <: DamlRecord[?]]

  // A choice where we don't know the type parameters at compile time.
  // ArgType: a subclass of com.daml.ledger.javaapi.data.codegen.DamlRecord
  // ResType: one of many different types:
  //   - a subclass of com.daml.ledger.javaapi.data.codegen.DamlRecord
  //   - a subclass of com.daml.ledger.javaapi.data.Value
  //   - com.daml.ledger.javaapi.data.codegen.ContractId (which is NOT a subclass of com.daml.ledger.javaapi.data.Value)
  //   - java.util.List
  //   - org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
  type GenericChoice = Choice[_ <: DamlRecord[?], DamlRecord[?], Any]

  // Only includes decoders for 1rst party daml contracts.
  // In particular, does not include splitwell.
  private val allDecoders = Seq(
    DecoderSpliceAmulet.contractDecoder,
    DecoderSpliceAmuletNameService.contractDecoder,
    DecoderSpliceDsoGovernance.contractDecoder,
    DecoderSpliceValidatorLifecycle.contractDecoder,
    DecoderSpliceWallet.contractDecoder,
    DecoderSpliceWalletPayments.contractDecoder,
  )

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def lookup(
      templateId: Identifier
  ): Either[String, C] = {
    val qualifiedName = QualifiedName(templateId)

    def templateMatches(id: Identifier) =
      QualifiedName(id) == qualifiedName

    val companion = allDecoders
      .collectFirst(
        Function.unlift(
          _.companions.asScala
            .find { case (id, _) => templateMatches(id) }
            .map(_._2)
        )
      )

    // The cast should not be necessary
    companion.map(_.asInstanceOf[C]).toRight(s"Could not find companion for $qualifiedName")
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def lookupChoice(
      companion: C,
      choice: String,
  ): Either[String, GenericChoice] = {
    import scala.language.existentials
    val result = companion.choices.asScala.get(choice)

    // Throw away all type safety
    result
      .map(_.asInstanceOf[GenericChoice])
      .toRight(
        s"Could not find companion for choice $choice of ${companion.getTemplateIdWithPackageId}"
      )
  }
}
