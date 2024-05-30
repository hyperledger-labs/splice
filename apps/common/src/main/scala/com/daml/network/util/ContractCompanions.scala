// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.{Choice, ContractId, DamlRecord}
import com.daml.network.codegen.java.{
  DecoderSpliceAmulet,
  DecoderSpliceAppManager,
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
  //   - com.daml.network.codegen.java.da.types.Tuple2
  type GenericChoice = Choice[_ <: DamlRecord[?], DamlRecord[?], Any]

  // Only includes decoders for 1rst party daml contracts.
  // In particular, does not include splitwell.
  private val allDecoders = Seq(
    DecoderSpliceAmulet.contractDecoder,
    DecoderSpliceAmuletNameService.contractDecoder,
    DecoderSpliceAppManager.contractDecoder,
    DecoderSpliceDsoGovernance.contractDecoder,
    DecoderSpliceValidatorLifecycle.contractDecoder,
    DecoderSpliceWallet.contractDecoder,
    DecoderSpliceWalletPayments.contractDecoder,
  )

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def lookup(
      templateId: Identifier
  ): Option[C] = {
    val qualifiedName = QualifiedName(templateId.getPackageId, templateId.getEntityName)

    def templateMatches(id: Identifier) =
      QualifiedName(id.getPackageId, id.getEntityName) == qualifiedName

    val companion = allDecoders
      .collectFirst(
        Function.unlift(
          _.companions.asScala
            .find { case (id, _) => templateMatches(id) }
            .map(_._2)
        )
      )

    // The cast should not be necessary
    companion.map(_.asInstanceOf[C])
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def lookupChoice(
      companion: C,
      choice: String,
  ): Option[GenericChoice] = {
    import scala.language.existentials
    val result = companion.choices.asScala.get(choice)

    // Throw away all type safety
    result.map(_.asInstanceOf[GenericChoice])
  }
}
