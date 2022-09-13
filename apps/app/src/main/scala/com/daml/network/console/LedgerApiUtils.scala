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
    // TODO (M1-92) Switch to users.get on the next Canton upgrade after 2022-09-13
    val userList = ledgerApi.ledger_api.users.list(
      filterUser = userId
    )
    if (userList.users.length != 1) {
      throw new RuntimeException(
        s"Expected exactly one user but got ${userList.users.length}: $userList"
      )
    } else {
      val user = userList.users(0)
      val primaryParty = user.primaryParty.getOrElse(
        throw new RuntimeException(s"User $userId has no primary party")
      );
      PartyId.tryFromLfParty(primaryParty)
    }
  }
}
