package com.daml.network.util

import com.daml.ledger.javaapi.data.{Command, CreateCommand, DamlRecord, Variant}
import com.daml.network.codegen.java.cc

import java.util.ArrayList
import scala.jdk.CollectionConverters.*

// TODO(#8126) Remove this file again once create commands auto up/downgrade the argument.
object UpgradeUtil {
  private def downgradeCoinValue(v: DamlRecord): DamlRecord = {
    require(v.getFields().size == 4)
    new DamlRecord(
      v.getFields.subList(0, 3)
    )
  }
  private def downgradeImportCrateValue(v: DamlRecord): DamlRecord = {
    require(v.getFields().size == 3)
    val newFields: ArrayList[DamlRecord.Field] = new ArrayList(v.getFields().subList(0, 2))
    val payload = v.getFields.get(2).getValue().asVariant().get()
    payload.getConstructor match {
      case "IP_Coin" =>
        newFields.add(
          new DamlRecord.Field(
            "payload",
            new Variant(payload.getConstructor, downgradeCoinValue(payload.getValue.asRecord.get)),
          )
        )
      case "IP_ValidatorLicense" =>
        newFields.add(new DamlRecord.Field("payload", payload))
      case c => throw new IllegalArgumentException(s"Unknown import payload constructor: $c")
    }
    new DamlRecord(
      newFields
    )
  }

  private def downgradeImportCrateCreate(cmd: Command): Command =
    cmd match {
      case create: CreateCommand
          if QualifiedName(create.getTemplateId) == QualifiedName("CC.CoinImport", "ImportCrate") =>
        new CreateCommand(
          create.getTemplateId,
          downgradeImportCrateValue(create.getCreateArguments),
        )
      case _ => throw new IllegalArgumentException(s"Unsupport command: $cmd")
    }

  def downgradeImportCrateCreate(payload: cc.coinimport.ImportCrate): Seq[Command] =
    payload.create.commands.asScala.map(downgradeImportCrateCreate).toSeq

  private def downgradeCoinCreate(cmd: Command): Command =
    cmd match {
      case create: CreateCommand
          if QualifiedName(create.getTemplateId) == QualifiedName("CC.Coin", "Coin") =>
        new CreateCommand(
          create.getTemplateId,
          downgradeCoinValue(create.getCreateArguments),
        )
      case _ => throw new IllegalArgumentException(s"Unsupport command: $cmd")
    }

  def downgradeCoinCreate(payload: cc.coin.Coin): Seq[Command] =
    payload.create.commands.asScala.map(downgradeCoinCreate).toSeq
}
