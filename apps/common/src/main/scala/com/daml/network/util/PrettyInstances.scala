package com.daml.network.util

import com.daml.ledger.client.binding.Primitive
import com.daml.ledger.javaapi
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.logging.pretty.PrettyUtil.*
import com.digitalasset.canton.util.ShowUtil.*

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
  def prettyContractIdString: Pretty[String] =
    coid => prettyPrimitiveContractId.treeOf(Primitive.ContractId[Any].apply(coid))

  implicit def prettyCodegenContractId: Pretty[javaapi.data.codegen.ContractId[_]] =
    coid => prettyContractIdString.treeOf(coid.contractId)

  implicit def prettyIdentifier: Pretty[javaapi.data.Identifier] = prettyOfString(id =>
    show"${id.getPackageId.readableHash}:${id.getModuleName.unquoted}:${id.getEntityName.unquoted}"
  )

  implicit def prettyCodegenDamlRecord: Pretty[javaapi.data.codegen.DamlRecord[_]] =
    r => prettyDamlRecord.treeOf(r.toValue)

  implicit def prettyDamlRecord: Pretty[javaapi.data.DamlRecord] = prettyOfClass(
    paramIfDefined("recordId", _.getRecordId.toScala),
    param("fields", _.getFields.asScala.map(f => f.getValue.toString.unquoted).toSeq),
  )

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

object PrettyInstances extends PrettyInstances
