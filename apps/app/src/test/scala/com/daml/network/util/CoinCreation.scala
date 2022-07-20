package com.daml.network.util

import com.daml.ledger.client.binding.{Contract, TemplateCompanion}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.console.{LocalDomainReference, LocalParticipantReference}
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.network.CC.Coin.Coin
import com.digitalasset.network.CC.Round.Round
import com.digitalasset.network.OpenBusiness.Fees.{ExpiringQuantity, RatePerRound}
import scala.concurrent.duration._
import com.digitalasset.canton.protocol.ContractIdSyntax._

import scala.concurrent.duration.FiniteDuration

/** Test utility trait that allows easy (synchronized) creation of coins
  */
trait CoinCreation extends EntitySyntax {
  this: BaseTest =>

  protected val round = 1L
  protected val ratePerRound = 0d

  protected def createCoin(
      owner: String,
      quantity: Double,
      domain: String,
      participant: LocalParticipantReference,
  )(implicit env: CoinTestConsoleEnvironment): Contract[Coin] = {
    val expiringQuantity: ExpiringQuantity =
      ExpiringQuantity(BigDecimal(quantity), Round(round), RatePerRound(BigDecimal(ratePerRound)))
    val ownerPartyId = owner.toPartyId(participant).toPrim
    val createCmd =
      Coin(
        ownerPartyId,
        ownerPartyId,
        expiringQuantity,
      ).create.command
    runCommand(Coin, owner, domain, participant, createCmd)
  }

  /** Functions from here on are in large part reused from Canton's AutoTransferTest */
  protected def runCommand[T <: com.daml.ledger.client.binding.Template[T]](
      companion: TemplateCompanion[T],
      owner: String,
      domain: String,
      participant: LocalParticipantReference,
      command: com.daml.ledger.api.v1.commands.Command,
  )(implicit env: CoinTestConsoleEnvironment): Contract[T] = {
    val tree =
      participant.ledger_api.commands.submit_flat(
        Seq(owner.toPartyId(participant)),
        Seq(command),
        workflowId = domain,
      )
    val contract = DecodeUtil.decodeAllCreated(companion)(tree).headOption.value
    assertInAcsSync(List(participant), domain.toDomainRef, contract.contractId.toLf)
    contract
  }

  protected def assertInAcsSync(
      participantRefs: Seq[LocalParticipantReference],
      domainRef: LocalDomainReference,
      contractId: LfContractId,
      timeout: FiniteDuration = 2.seconds,
  ): Unit =
    for (participantRef <- participantRefs) {
      eventually(timeout) {
        import com.digitalasset.canton.console.ConsoleEnvironment.Implicits._
        val contracts =
          participantRef.testing.acs_search(domainRef.name, filterId = contractId.coid)
        assert(
          contracts.size == 1,
          s"Participant ${participantRef.name} unexpectedly does not have the active contract $contractId",
        )
      }
    }
}
