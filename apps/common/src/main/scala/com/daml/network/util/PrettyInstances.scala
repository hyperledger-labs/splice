package com.daml.network.util

import com.daml.ledger.client.binding.Primitive
import com.daml.ledger.javaapi
import com.digitalasset.canton.logging.pretty.PrettyUtil.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.util.ShowUtil.*
import pprint.Tree

/** Extension of Canton's pretty instances with additional ones for:
  * - java client bindings (com.daml.ledger.javaapi.data)
  *
  * We recommend importing this PrettyInstances instead of the one from Canton.
  */
trait PrettyInstances extends com.digitalasset.canton.logging.pretty.PrettyInstances {

  // NOTE: instances are added in an on-demand fashion. Please don't hesitate to add more.
  import scala.jdk.CollectionConverters.*
  import scala.jdk.OptionConverters.*

  // Not made implicit to avoid interpreting all Strings as contract-ids
  private[PrettyInstances] def prettyContractIdString: Pretty[String] =
    coid => prettyPrimitiveContractId.treeOf(Primitive.ContractId[Any].apply(coid))

  implicit def prettyCodegenContractId: Pretty[javaapi.data.codegen.ContractId[?]] =
    coid => prettyContractIdString.treeOf(coid.contractId)

  implicit def prettyIdentifier: Pretty[javaapi.data.Identifier] = prettyOfString(id =>
    show"${id.getPackageId.readableHash}:${id.getModuleName.unquoted}:${id.getEntityName.unquoted}"
  )

  implicit def prettyCodegenDamlRecord: Pretty[javaapi.data.codegen.DamlRecord[?]] =
    r => prettyDamlRecord.treeOf(r.toValue)

  implicit def prettyCodegenVariant: Pretty[javaapi.data.codegen.Variant[?]] =
    r => prettyDamlVariant.treeOf(r.toValue)

  implicit def prettyDamlParty: Pretty[javaapi.data.Party] =
    prettyNode("PartyId", v => Some(prettyOfString(prettyUidString).treeOf(v.getValue)))

  implicit def prettyDamlContractId: Pretty[javaapi.data.ContractId] =
    prettyNode("ContractId", v => Some(prettyContractIdString.treeOf(v.getValue)))

  implicit def prettyDamlText: Pretty[javaapi.data.Text] =
    prettyOfString(_.getValue.doubleQuoted.show)

  implicit def prettyDamlInt64: Pretty[javaapi.data.Int64] =
    prettyOfString[javaapi.data.Int64](_.getValue.toString)

  implicit def prettyDamlNumeric: Pretty[javaapi.data.Numeric] =
    prettyNode(
      "Numeric",
      unnamedParam(prettyOfString[javaapi.data.Numeric](_.getValue.toString).treeOf),
    )

  implicit def prettyDamlBool: Pretty[javaapi.data.Bool] =
    prettyOfString[javaapi.data.Bool](_.getValue.toString)

  implicit def prettyDamlUnit: Pretty[javaapi.data.Unit] = prettyNode(
    "unit" // lowercase to distinguish from a user-defined 'Unit'
  )
  implicit def prettyDamlTimestamp: Pretty[javaapi.data.Timestamp] =
    prettyNode(
      "Timestamp",
      param("ms", v => Tree.Literal(v.getMicroseconds.toString)),
      param("str", v => Tree.Literal(v.toInstant.toString)),
    )

  implicit def prettyDamlDate: Pretty[javaapi.data.Date] =
    prettyNode(
      "Date",
      param("d", v => Tree.Literal(v.getValue.toEpochDay.toString)),
      param("str", v => Tree.Literal(v.getValue.toString)),
    )

  implicit def prettyDamlRecord: Pretty[javaapi.data.DamlRecord] = r => {
    prettyNode[javaapi.data.DamlRecord](
      "Record",
      paramIfDefined("recordId", _.getRecordId.toScala),
      param("fields", _.getFields.asScala.map(f => f.getValue).toSeq),
    ).treeOf(r)
  }

  implicit def prettyDamlVariant: Pretty[javaapi.data.Variant] = inst => {
    prettyNode[javaapi.data.Value](inst.getConstructor, v => Some(prettyDamlValue.treeOf(v)))
      .treeOf(inst.getValue)
  }

  implicit def prettyDamlEnum: Pretty[javaapi.data.DamlEnum] =
    prettyOfString[javaapi.data.DamlEnum](_.getConstructor)

  implicit def prettyDamlList: Pretty[javaapi.data.DamlList] = inst => {
    treeOfIterable("List", inst.stream().iterator().asScala.toSeq)
  }

  implicit def prettyDamlOptional: Pretty[javaapi.data.DamlOptional] = inst => {
    prettyOption[javaapi.data.Value].treeOf(inst.getValue.toScala)
  }

  implicit def prettyDamlTextMap: Pretty[javaapi.data.DamlTextMap] = inst => {
    val elements = inst.stream.iterator().asScala.toSeq
    treeOfIterable(
      "TextMap",
      elements.map(entry =>
        Tree.Infix(Tree.Literal(entry.getKey), "->", prettyDamlValue.treeOf(entry.getValue))
      ),
    )
  }

  implicit def prettyDamlGenMap: Pretty[javaapi.data.DamlGenMap] = inst => {
    val elements = inst.stream.iterator().asScala.toSeq
    treeOfIterable(
      "GenMap",
      elements.map(entry =>
        Tree.Infix(
          prettyDamlValue.treeOf(entry.getKey),
          "->",
          prettyDamlValue.treeOf(entry.getValue),
        )
      ),
    )
  }

  implicit def prettyDamlValue: Pretty[javaapi.data.Value] = {
    case r: javaapi.data.DamlRecord => prettyDamlRecord.treeOf(r)
    case v: javaapi.data.Variant => prettyDamlVariant.treeOf(v)
    case x: javaapi.data.DamlEnum => prettyDamlEnum.treeOf(x)
    case x: javaapi.data.ContractId => prettyDamlContractId.treeOf(x)
    case l: javaapi.data.DamlList => prettyDamlList.treeOf(l)
    case x: javaapi.data.Int64 => prettyDamlInt64.treeOf(x)
    case x: javaapi.data.Numeric => prettyDamlNumeric.treeOf(x)
    case x: javaapi.data.Text => prettyDamlText.treeOf(x)
    case x: javaapi.data.Timestamp => prettyDamlTimestamp.treeOf(x)
    case x: javaapi.data.Party => prettyDamlParty.treeOf(x)
    case x: javaapi.data.Bool => prettyDamlBool.treeOf(x)
    case x: javaapi.data.Unit => prettyDamlUnit.treeOf(x)
    case x: javaapi.data.Date => prettyDamlDate.treeOf(x)
    case o: javaapi.data.DamlOptional => prettyDamlOptional.treeOf(o)
    case m: javaapi.data.DamlTextMap => prettyDamlTextMap.treeOf(m)
    case m: javaapi.data.DamlGenMap => prettyDamlGenMap.treeOf(m)

    // fallback, as there is no exhaustiveness check for matches on Java classes
    case fallback => prettyOfString[javaapi.data.Value](_.toString).treeOf(fallback)
  }

  implicit def prettyCreatedEvent: Pretty[javaapi.data.CreatedEvent] = prettyOfClass(
    param("contractId", ev => prettyContractIdString.treeOf(ev.getContractId)),
    param("templateId", _.getTemplateId),
    param("payload", _.getArguments),
  )

  implicit def prettyArchivedEvent: Pretty[javaapi.data.ArchivedEvent] = prettyOfClass(
    param("contractId", ev => prettyContractIdString.treeOf(ev.getContractId)),
    param("templateId", _.getTemplateId),
  )
}

object PrettyInstances extends PrettyInstances {

  /** Helper class to pretty-print contract-id references together with their template or interface identifier. */
  case class PrettyContractId(
      identifier: javaapi.data.Identifier,
      contractId: javaapi.data.codegen.ContractId[_],
  ) extends PrettyPrinting {

    override def pretty: Pretty[PrettyContractId.this.type] =
      prettyNode(
        "ContractId",
        param("id", typedCid => prettyContractIdString.treeOf(typedCid.contractId.contractId)),
        param("type", typedCid => prettyIdentifier.treeOf(typedCid.identifier)),
      )
  }
}
