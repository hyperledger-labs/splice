// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.store

import com.daml.network.codegen.java.splice.dsorules.ActionRequiringConfirmation
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AnsEntryContext,
  ARC_AmuletRules,
  ARC_DsoRules,
}
import com.daml.network.store.StoreErrors
import com.daml.network.store.TxLogStore.TxLogEntryTypeMappers
import com.digitalasset.canton.config.CantonRequireTypes.String3

trait TxLogEntry

object TxLogEntry extends StoreErrors {

  def encode(entry: TxLogEntry): (String3, String) = {
    import scalapb.json4s.JsonFormat
    val entryType = entry match {
      case _: ErrorTxLogEntry => EntryType.ErrorTxLogEntry
      case _: VoteRequestTxLogEntry => EntryType.VoteRequestTxLogEntry
      case _ => throw txEncodingFailed()
    }
    val jsonValue = entry match {
      case e: scalapb.GeneratedMessage => JsonFormat.toJsonString(e)
      case _ => throw txEncodingFailed()
    }
    (entryType, jsonValue)
  }
  def decode(entryType: String3, json: String): TxLogEntry = {
    import scalapb.json4s.JsonFormat.fromJsonString as from
    try {
      entryType match {
        case EntryType.ErrorTxLogEntry => from[ErrorTxLogEntry](json)
        case EntryType.VoteRequestTxLogEntry => from[VoteRequestTxLogEntry](json)
        case _ => throw txDecodingFailed()
      }
    } catch {
      case _: RuntimeException => throw txDecodingFailed()
    }
  }

  object EntryType {
    val ErrorTxLogEntry: String3 = String3.tryCreate("err")
    val DefiniteVoteTxLogEntry: String3 = String3.tryCreate("dv")
    val VoteRequestTxLogEntry: String3 = String3.tryCreate("v")
  }

  def mapActionName(
      action: ActionRequiringConfirmation
  ): String = {
    action match {
      case arcDsoRules: ARC_DsoRules =>
        arcDsoRules.dsoAction.getClass.getSimpleName
      case arcAmuletRules: ARC_AmuletRules =>
        arcAmuletRules.amuletRulesAction.getClass.getSimpleName
      case arcAnsEntryContext: ARC_AnsEntryContext =>
        arcAnsEntryContext.ansEntryContextAction.getClass.getSimpleName
      case _ => ""
    }
  }

  trait TypeMappers extends TxLogEntryTypeMappers {}
}
