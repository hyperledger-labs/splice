// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.{
  Choice,
  ContractId,
  DamlRecord,
  Contract as JavaGenContract,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationrequestv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.{
  DecoderSpliceAmulet,
  DecoderSpliceAmuletNameService,
  DecoderSpliceDsoGovernance,
  DecoderSpliceValidatorLifecycle,
  DecoderSpliceWallet,
  DecoderSpliceWalletPayments,
}

import scala.jdk.CollectionConverters.*

// TODO (#17282): Replace with usage of com.digitalasset.transcode
object ContractCompanions {
  type C = Contract.Companion.Template[_ <: ContractId[?], _ <: DamlRecord[?]]
  type I = Contract.Companion.Interface[_ <: ContractId[?], _ <: JavaGenContract[_ <: ContractId[
    ?
  ], _ <: DamlRecord[?]], _ <: DamlRecord[?]]

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

  // Unfortunately interfaces need to be declared explicitly, as there's no auto-generated list of decoders
  private val interfaces = Seq(
    metadatav1.RegistryAppInstall.INTERFACE,
    holdingv1.Holding.INTERFACE,
    transferinstructionv1.TransferInstruction.INTERFACE,
    transferinstructionv1.TransferFactory.INTERFACE,
    allocationv1.Allocation.INTERFACE,
    allocationrequestv1.AllocationRequest.INTERFACE,
    allocationinstructionv1.AllocationInstruction.INTERFACE,
    allocationinstructionv1.AllocationFactory.INTERFACE,
  )

  private def templatesMatch(id: Identifier, qualifiedName: QualifiedName) =
    QualifiedName(id) == qualifiedName

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def lookup(
      templateId: Identifier
  ): Either[String, C] = {
    val qualifiedName = QualifiedName(templateId)

    val companion = allDecoders
      .collectFirst(
        Function.unlift(
          _.companions.asScala
            .find { case (id, _) => templatesMatch(id, qualifiedName) }
            .map(_._2)
        )
      )

    // The cast should not be necessary
    companion
      .map(_.asInstanceOf[C])
      .toRight(s"Could not find template companion for $qualifiedName")
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def lookupInterface(interfaceId: Identifier): Either[String, I] = {
    val qualifiedName = QualifiedName(interfaceId)

    interfaces
      .find(interface => templatesMatch(interface.TEMPLATE_ID, qualifiedName))
      .map(_.asInstanceOf[I])
      .toRight {
        val err = s"Could not find interface companion for $qualifiedName"
        err
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def lookupChoice(
      companion: C,
      interfaceCompanion: Option[I],
      choice: String,
  ): Either[String, GenericChoice] = {
    import scala.language.existentials
    val result = companion.choices.asScala
      .get(choice)
      .orElse(interfaceCompanion.flatMap(_.choices.asScala.get(choice)))

    // Throw away all type safety
    result
      .map(_.asInstanceOf[GenericChoice])
      .toRight {
        val err =
          s"Could not find companion for choice $choice of ${companion.getTemplateIdWithPackageId}"
        err
      }
  }
}
