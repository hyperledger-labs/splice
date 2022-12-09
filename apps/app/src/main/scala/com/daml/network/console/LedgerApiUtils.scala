package com.daml.network.console

import com.daml.ledger.api.v1.transaction.TransactionTree
import com.daml.ledger.javaapi.data.codegen.Update
import com.daml.ledger.javaapi.data.{TransactionTree => JavaTransactionTree}
import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.console.commands.BaseLedgerApiAdministration
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

object LedgerApiUtils {
  def submitWithResult[T](
      ledgerApi: BaseLedgerApiAdministration,
      userId: String,
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Update[T],
      commandId: Option[String] = None,
  ): T = {
    val tree = ledgerApi.ledger_api.commands.submitJava(
      actAs,
      update.commands.asScala.toSeq,
      workflowId = "",
      commandId.getOrElse(""),
      readAs = readAs,
      applicationId = userId,
      optTimeout = None,
    )
    CoinLedgerConnection.decodeExerciseResult(
      update,
      JavaTransactionTree.fromProto(TransactionTree.toJavaProto(tree)),
    )
  }

  def getUserPrimaryParty(ledgerApi: BaseLedgerApiAdministration, userId: String) = {
    val user = ledgerApi.ledger_api.users.get(userId)
    val primaryParty = user.primaryParty.getOrElse(
      throw new RuntimeException(s"User $userId has no primary party")
    );
    PartyId.tryFromLfParty(primaryParty)
  }

  def getUserReadAs(ledgerApi: BaseLedgerApiAdministration, userId: String): Set[PartyId] = {
    val rights = ledgerApi.ledger_api.users.rights.list(userId)
    rights.readAs.map(PartyId.tryFromLfParty(_))
  }
}
