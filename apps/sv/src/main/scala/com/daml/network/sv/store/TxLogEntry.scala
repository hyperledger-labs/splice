package com.daml.network.sv.store

import com.daml.network.codegen.java.cn.svcrules.ActionRequiringConfirmation
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.{
  ARC_CnsEntryContext,
  ARC_CoinRules,
  ARC_SvcRules,
}
import com.daml.network.store.StoreErrors
import com.daml.network.store.TxLogStore.TxLogEntryTypeMappers
import com.digitalasset.canton.config.CantonRequireTypes.String3
import scalapb.TypeMapper

trait TxLogEntry

object TxLogEntry extends StoreErrors {

  def encode(entry: TxLogEntry): (String3, String) = {
    import scalapb.json4s.JsonFormat
    val entryType = entry match {
      case _: ErrorTxLogEntry => EntryType.ErrorTxLogEntry
      case _: DefiniteVoteTxLogEntry => EntryType.DefiniteVoteTxLogEntry
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
        case EntryType.DefiniteVoteTxLogEntry => from[DefiniteVoteTxLogEntry](json)
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
      case arcSvcRules: ARC_SvcRules =>
        arcSvcRules.svcAction.getClass.getSimpleName
      case arcCoinRules: ARC_CoinRules =>
        arcCoinRules.coinRulesAction.getClass.getSimpleName
      case arcCnsEntryContext: ARC_CnsEntryContext =>
        arcCnsEntryContext.cnsEntryContextAction.getClass.getSimpleName
      case _ => ""
    }
  }

  trait TypeMappers extends TxLogEntryTypeMappers {
    protected implicit val voteResultType: TypeMapper[
      com.google.protobuf.struct.Struct,
      com.daml.network.codegen.java.cn.svcrules.VoteResult,
    ] =
      TypeMapper[
        com.google.protobuf.struct.Struct,
        com.daml.network.codegen.java.cn.svcrules.VoteResult,
      ](x => {
        val javaProto = com.google.protobuf.struct.Struct.toJavaProto(x)
        val string = com.google.protobuf.util.JsonFormat.printer().print(javaProto)
        com.daml.network.codegen.java.cn.svcrules.VoteResult.fromJson(string)
      })(x => {
        val string = x.toJson
        val builder = com.google.protobuf.Struct.newBuilder()
        com.google.protobuf.util.JsonFormat
          .parser()
          .merge(string, builder)
        com.google.protobuf.struct.Struct.fromJavaProto(builder.build())
      })

    protected implicit val voteRequestResult2Type: TypeMapper[
      com.google.protobuf.struct.Struct,
      com.daml.network.codegen.java.cn.svcrules.VoteRequestResult2,
    ] =
      TypeMapper[
        com.google.protobuf.struct.Struct,
        com.daml.network.codegen.java.cn.svcrules.VoteRequestResult2,
      ](x => {
        val javaProto = com.google.protobuf.struct.Struct.toJavaProto(x)
        val string = com.google.protobuf.util.JsonFormat.printer().print(javaProto)
        com.daml.network.codegen.java.cn.svcrules.VoteRequestResult2.fromJson(string)
      })(x => {
        val string = x.toJson
        val builder = com.google.protobuf.Struct.newBuilder()
        com.google.protobuf.util.JsonFormat
          .parser()
          .merge(string, builder)
        com.google.protobuf.struct.Struct.fromJavaProto(builder.build())
      })
  }
}
