package com.daml.network.console

import com.daml.ledger.client.binding.{Primitive, ValueDecoder}
import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.console.commands.BaseLedgerApiAdministration
import com.digitalasset.canton.topology.PartyId

object LedgerApiUtils {
  def submitWithResult[T](
      ledgerApi: BaseLedgerApiAdministration,
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: Primitive.Update[T],
      commandId: Option[String] = None,
  )(implicit decoder: ValueDecoder[T]): T = {
    val tree = ledgerApi.ledger_api.commands.submit(
      actAs,
      Seq(update.command),
      workflowId = "",
      commandId.getOrElse(""),
      readAs = readAs,
      optTimeout = None,
    )
    CoinLedgerConnection.decodeExerciseResult(update.toString, tree)
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
