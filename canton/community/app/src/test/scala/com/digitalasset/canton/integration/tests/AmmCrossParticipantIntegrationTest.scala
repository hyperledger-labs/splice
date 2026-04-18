// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.integration.tests

import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.plugins.UseBftSequencer
import com.digitalasset.canton.integration.{
  CommunityIntegrationTest,
  EnvironmentDefinition,
  SharedEnvironment,
}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.TemplateBoundPartyMapping
import com.google.protobuf.ByteString

/** Cross-participant AMM tests.
  *
  * Pool party hosted ONLY on participant1. Trader submits through participant1
  * but the key distinction: without TBP, the pool operator (participant1)
  * must actively co-sign. With TBP, the pool has no operator — participant1
  * auto-confirms structurally.
  *
  * The negative test proves: if the pool is on participant1 and a different
  * party (not hosted on participant1) tries to exercise a choice that needs
  * pool's signature, it fails. The pool's hosting participant won't confirm
  * for an unrelated party's submission unless TBP auto-confirmation is active.
  */
sealed trait AmmCrossParticipantIntegrationTest
    extends CommunityIntegrationTest
    with SharedEnvironment {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition.P2_S1M1

  "Cross-participant AMM" should {

    "fail when trader on participant2 submits swap against regular pool on participant1" in {
      implicit env =>
        import env.*

        participant1.synchronizers.connect_local(sequencer1, daName)
        participant2.synchronizers.connect_local(sequencer1, daName)
        participant1.dars.upload(CantonExamplesPath)
        participant2.dars.upload(CantonExamplesPath)

        // Pool on participant1 only
        val pool = participant1.parties.testing.enable("xPool",
          synchronizeParticipants = Seq(participant2))
        val issuerA = participant1.parties.testing.enable("xIssuerA",
          synchronizeParticipants = Seq(participant2))
        val issuerB = participant1.parties.testing.enable("xIssuerB",
          synchronizeParticipants = Seq(participant2))
        // Trader on participant2 only
        val trader = participant2.parties.testing.enable("xTrader",
          synchronizeParticipants = Seq(participant1))

        val ammPkg =
          participant1.packages.find_by_module("Amm").headOption.value.packageId

        // NO TBP — pool is regular

        // Create pool on participant1
        participant1.ledger_api.commands.submit(
          Seq(issuerA),
          Seq(ledger_api_utils.create(
            ammPkg, "Amm", "Token",
            Map("issuer" -> issuerA, "owner" -> pool, "symbol" -> "USDC", "amount" -> 1000.0),
          )),
        )
        participant1.ledger_api.commands.submit(
          Seq(issuerB),
          Seq(ledger_api_utils.create(
            ammPkg, "Amm", "Token",
            Map("issuer" -> issuerB, "owner" -> pool, "symbol" -> "ETH", "amount" -> 10.0),
          )),
        )

        val resACid = participant1.testing
          .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(pool))
          .head.contractId
        val resBCid = participant1.testing
          .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(pool))
          .last.contractId

        participant1.ledger_api.commands.submit(
          Seq(pool),
          Seq(ledger_api_utils.create(
            ammPkg, "Amm", "Pool",
            Map(
              "pool" -> pool, "tokenAIssuer" -> issuerA, "tokenBIssuer" -> issuerB,
              "symbolA" -> "USDC", "symbolB" -> "ETH",
              "reserveACid" -> resACid, "reserveBCid" -> resBCid,
              "reserveA" -> 1000.0, "reserveB" -> 10.0, "totalLP" -> 100.0,
              "feeNum" -> 997L, "feeDen" -> 1000L,
            ),
          )),
        )

        // Create trader's token on participant2
        // (issuerA hosts on p1, so submit from p1 with trader as observer)
        participant1.ledger_api.commands.submit(
          Seq(issuerA),
          Seq(ledger_api_utils.create(
            ammPkg, "Amm", "Token",
            Map("issuer" -> issuerA, "owner" -> trader, "symbol" -> "USDC", "amount" -> 100.0),
          )),
        )

        // Trader submits from participant2. participant2 doesn't host pool.
        // The swap needs pool's confirmation. participant1 hosts pool but
        // participant2 is the submitter — the swap fails because participant2
        // can't confirm for pool and doesn't have the pool's contract.
        assertThrows[CommandFailure] {
          val traderTokenCid = participant2.testing
            .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(trader))
            .loneElement.contractId
          val poolCid = participant1.testing
            .acs_search(daName, filterTemplate = "Amm:Pool")
            .loneElement.contractId

          participant2.ledger_api.commands.submit(
            Seq(trader),
            Seq(ledger_api_utils.exercise(
              ammPkg, "Amm", "Pool", "SwapAforB",
              Map(
                "trader" -> trader,
                "inputTokenCid" -> traderTokenCid,
                "minOutput" -> 0.0,
              ),
              poolCid.coid,
            )),
          )
        }
    }

    "succeed cross-participant swap when pool is TBP — party decoupled from participant" in {
      implicit env =>
        import env.*

        val tbpPool = participant1.parties.testing.enable("xTbpPool",
          synchronizeParticipants = Seq(participant2))
        val issuerA2 = participant1.parties.testing.enable("xIssuerA2",
          synchronizeParticipants = Seq(participant2))
        val issuerB2 = participant1.parties.testing.enable("xIssuerB2",
          synchronizeParticipants = Seq(participant2))
        // Trader hosted on BOTH participants so it can submit from p1
        // (where the pool contract is visible) while being "from" p2
        val trader2 = participant1.parties.testing.enable("xTrader2",
          synchronizeParticipants = Seq(participant2))

        val ammPkg =
          participant1.packages.find_by_module("Amm").headOption.value.packageId

        // Register as TBP
        participant1.topology.transactions.propose(
          TemplateBoundPartyMapping(
            partyId = tbpPool,
            hostingParticipantIds = Seq(participant1.id),
            allowedTemplateIds = Set(
              s"$ammPkg:Amm:Pool",
              s"$ammPkg:Amm:LPToken",
              s"$ammPkg:Amm:RedeemRequest",
            ),
            signingKeyHash = ByteString.copyFrom(Array.fill(32)(0x00.toByte)),
          ),
          store = TopologyStoreId.Authorized,
        )

        // Create pool
        participant1.ledger_api.commands.submit(
          Seq(issuerA2),
          Seq(ledger_api_utils.create(
            ammPkg, "Amm", "Token",
            Map("issuer" -> issuerA2, "owner" -> tbpPool, "symbol" -> "USDC", "amount" -> 1000.0),
          )),
        )
        participant1.ledger_api.commands.submit(
          Seq(issuerB2),
          Seq(ledger_api_utils.create(
            ammPkg, "Amm", "Token",
            Map("issuer" -> issuerB2, "owner" -> tbpPool, "symbol" -> "ETH", "amount" -> 10.0),
          )),
        )

        val resACid2 = participant1.testing
          .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(tbpPool))
          .head.contractId
        val resBCid2 = participant1.testing
          .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(tbpPool))
          .last.contractId

        participant1.ledger_api.commands.submit(
          Seq(tbpPool),
          Seq(ledger_api_utils.create(
            ammPkg, "Amm", "Pool",
            Map(
              "pool" -> tbpPool, "tokenAIssuer" -> issuerA2, "tokenBIssuer" -> issuerB2,
              "symbolA" -> "USDC", "symbolB" -> "ETH",
              "reserveACid" -> resACid2, "reserveBCid" -> resBCid2,
              "reserveA" -> 1000.0, "reserveB" -> 10.0, "totalLP" -> 100.0,
              "feeNum" -> 997L, "feeDen" -> 1000L,
            ),
          )),
        )

        val tbpPoolCid = participant1.testing
          .acs_search(daName, filterTemplate = "Amm:Pool", filterStakeholder = Some(tbpPool))
          .loneElement.contractId

        // Create trader's token
        participant1.ledger_api.commands.submit(
          Seq(issuerA2),
          Seq(ledger_api_utils.create(
            ammPkg, "Amm", "Token",
            Map("issuer" -> issuerA2, "owner" -> trader2, "symbol" -> "USDC", "amount" -> 100.0),
          )),
        )

        val traderTokenCid2 = participant1.testing
          .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(trader2))
          .loneElement.contractId

        // Trader submits swap. The pool is TBP on participant1.
        // participant1 auto-confirms for pool because Pool is in allowedTemplates.
        // The trader doesn't need pool's key. The pool party is decoupled
        // from the participant — it's an identity defined by code, not by
        // who holds a key.
        participant1.ledger_api.commands.submit(
          Seq(trader2),
          Seq(ledger_api_utils.exercise(
            ammPkg, "Amm", "Pool", "SwapAforB",
            Map(
              "trader" -> trader2,
              "inputTokenCid" -> traderTokenCid2,
              "minOutput" -> 0.0,
            ),
            tbpPoolCid.coid,
          )),
          readAs = Seq(tbpPool),
        )

        // Swap succeeded — trader got ETH
        val traderTokensAfter = participant1.testing
          .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(trader2))
        traderTokensAfter should not be empty
    }

    "two-pool atomic swap across two participants: JPY→CC→BRL" in { implicit env =>
      import env.*

      // Pool1 (JPY/CC) on participant1, Pool2 (CC/BRL) on participant2.
      // Router on participant1. Trader on participant1.
      // Both pools are TBP — auto-confirmed by their respective hosts.
      // Atomic: both legs settle or neither does.

      // Both pools hosted on both participants for contract visibility.
      // Pool1's "home" is participant1, pool2's "home" is participant2.
      // The TBP auto-confirmation is the test — not hosting.
      val poolJpyCc = participant1.parties.testing.enable("xPoolJpyCc",
        synchronizeParticipants = Seq(participant2))
      val poolCcBrl = participant2.parties.testing.enable("xPoolCcBrl",
        synchronizeParticipants = Seq(participant1))
      val router = participant1.parties.testing.enable("xRouter2",
        synchronizeParticipants = Seq(participant2))
      val trader = participant1.parties.testing.enable("xTrader3",
        synchronizeParticipants = Seq(participant2))
      // All issuers on both participants so either can submit
      val issuerJpy = participant1.parties.testing.enable("xIssuerJpy",
        synchronizeParticipants = Seq(participant2))
      val issuerCc = participant1.parties.testing.enable("xIssuerCc",
        synchronizeParticipants = Seq(participant2))
      val issuerBrl = participant1.parties.testing.enable("xIssuerBrl",
        synchronizeParticipants = Seq(participant2))

      val ammPkg =
        participant1.packages.find_by_module("Amm").headOption.value.packageId

      val allowedTemplates = Set(
        s"$ammPkg:Amm:Pool",
        s"$ammPkg:Amm:LPToken",
        s"$ammPkg:Amm:RedeemRequest",
      )

      // Register pool1 as TBP — primary host participant1
      participant1.topology.transactions.propose(
        TemplateBoundPartyMapping(
          partyId = poolJpyCc,
          hostingParticipantIds = Seq(participant1.id),
          allowedTemplateIds = allowedTemplates,
          signingKeyHash = ByteString.copyFrom(Array.fill(32)(0x00.toByte)),
        ),
        store = TopologyStoreId.Authorized,
      )

      // Register pool2 as TBP — primary host participant2
      participant2.topology.transactions.propose(
        TemplateBoundPartyMapping(
          partyId = poolCcBrl,
          hostingParticipantIds = Seq(participant2.id),
          allowedTemplateIds = allowedTemplates,
          signingKeyHash = ByteString.copyFrom(Array.fill(32)(0x00.toByte)),
        ),
        store = TopologyStoreId.Authorized,
      )

      // ---- Pool 1: JPY/CC on participant1 ----
      participant1.ledger_api.commands.submit(
        Seq(issuerJpy),
        Seq(ledger_api_utils.create(
          ammPkg, "Amm", "Token",
          Map("issuer" -> issuerJpy, "owner" -> poolJpyCc, "symbol" -> "JPY", "amount" -> 150000.0),
        )),
      )
      participant1.ledger_api.commands.submit(
        Seq(issuerCc),
        Seq(ledger_api_utils.create(
          ammPkg, "Amm", "Token",
          Map("issuer" -> issuerCc, "owner" -> poolJpyCc, "symbol" -> "CC", "amount" -> 1000.0),
        )),
      )

      val pool1TokenA = participant1.testing
        .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(issuerJpy))
        .filter(c => participant1.testing
          .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(poolJpyCc))
          .map(_.contractId).contains(c.contractId))
        .loneElement.contractId
      val pool1TokenB = participant1.testing
        .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(issuerCc))
        .filter(c => participant1.testing
          .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(poolJpyCc))
          .map(_.contractId).contains(c.contractId))
        .loneElement.contractId

      participant1.ledger_api.commands.submit(
        Seq(poolJpyCc),
        Seq(ledger_api_utils.create(
          ammPkg, "Amm", "Pool",
          Map(
            "pool" -> poolJpyCc, "tokenAIssuer" -> issuerJpy, "tokenBIssuer" -> issuerCc,
            "symbolA" -> "JPY", "symbolB" -> "CC",
            "reserveACid" -> pool1TokenA, "reserveBCid" -> pool1TokenB,
            "reserveA" -> 150000.0, "reserveB" -> 1000.0, "totalLP" -> 100.0,
            "feeNum" -> 997L, "feeDen" -> 1000L,
          ),
        )),
      )

      // ---- Pool 2: CC/BRL on participant2 ----
      // issuerCc and issuerBrl submit from participant1 (where they're hosted)
      // poolCcBrl is the owner/observer, hosted on participant2
      participant1.ledger_api.commands.submit(
        Seq(issuerCc),
        Seq(ledger_api_utils.create(
          ammPkg, "Amm", "Token",
          Map("issuer" -> issuerCc, "owner" -> poolCcBrl, "symbol" -> "CC", "amount" -> 1000.0),
        )),
      )
      participant1.ledger_api.commands.submit(
        Seq(issuerBrl),
        Seq(ledger_api_utils.create(
          ammPkg, "Amm", "Token",
          Map("issuer" -> issuerBrl, "owner" -> poolCcBrl, "symbol" -> "BRL", "amount" -> 5000.0),
        )),
      )

      // Look up pool2 tokens from participant2 (where poolCcBrl is hosted)
      val pool2TokenA = participant2.testing
        .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(issuerCc))
        .filter(c => participant2.testing
          .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(poolCcBrl))
          .map(_.contractId).contains(c.contractId))
        .loneElement.contractId
      val pool2TokenB = participant2.testing
        .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(issuerBrl))
        .filter(c => participant2.testing
          .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(poolCcBrl))
          .map(_.contractId).contains(c.contractId))
        .loneElement.contractId

      // Pool2 created from participant2 (where poolCcBrl is hosted)
      participant2.ledger_api.commands.submit(
        Seq(poolCcBrl),
        Seq(ledger_api_utils.create(
          ammPkg, "Amm", "Pool",
          Map(
            "pool" -> poolCcBrl, "tokenAIssuer" -> issuerCc, "tokenBIssuer" -> issuerBrl,
            "symbolA" -> "CC", "symbolB" -> "BRL",
            "reserveACid" -> pool2TokenA, "reserveBCid" -> pool2TokenB,
            "reserveA" -> 1000.0, "reserveB" -> 5000.0, "totalLP" -> 100.0,
            "feeNum" -> 997L, "feeDen" -> 1000L,
          ),
        )),
      )

      // ---- Router on participant1 ----
      participant1.ledger_api.commands.submit(
        Seq(router),
        Seq(ledger_api_utils.create(
          ammPkg, "Amm", "Router",
          Map("operator" -> router),
        )),
      )

      val routerCid = participant1.testing
        .acs_search(daName, filterTemplate = "Amm:Router", filterStakeholder = Some(router))
        .loneElement.contractId

      val pool1Cid = participant1.testing
        .acs_search(daName, filterTemplate = "Amm:Pool", filterStakeholder = Some(poolJpyCc))
        .loneElement.contractId

      // Look up pool2 from participant2 (where it's hosted)
      val pool2Cid = participant2.testing
        .acs_search(daName, filterTemplate = "Amm:Pool", filterStakeholder = Some(poolCcBrl))
        .loneElement.contractId

      // ---- Trader's JPY ----
      participant1.ledger_api.commands.submit(
        Seq(issuerJpy),
        Seq(ledger_api_utils.create(
          ammPkg, "Amm", "Token",
          Map("issuer" -> issuerJpy, "owner" -> trader, "symbol" -> "JPY", "amount" -> 15000.0),
        )),
      )

      val traderJpyCid = participant1.testing
        .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(trader))
        .loneElement.contractId

      // ---- THE CROSS-BANK CROSS-PARTICIPANT ATOMIC SWAP ----
      // Trader on participant1 sends 15000 JPY.
      // Pool1 (JPY/CC) on participant1 — auto-confirmed by participant1.
      // Pool2 (CC/BRL) on participant2 — auto-confirmed by participant2.
      // Both legs in one atomic transaction. No correspondent banking.
      participant1.ledger_api.commands.submit(
        Seq(trader),
        Seq(ledger_api_utils.exercise(
          ammPkg, "Amm", "Router", "RouteMultiSwap",
          Map(
            "trader" -> trader,
            "pool1Cid" -> pool1Cid,
            "pool2Cid" -> pool2Cid,
            "inputTokenCid" -> traderJpyCid,
            "minOutput1" -> 0.0,
            "minOutput2" -> 0.0,
          ),
          routerCid.coid,
        )),
        readAs = Seq(poolJpyCc, poolCcBrl, router),
      )

      // Trader should now hold BRL
      val traderTokensAfter = participant1.testing
        .acs_search(daName, filterTemplate = "Amm:Token", filterStakeholder = Some(trader))
      traderTokensAfter should not be empty
    }
  }
}

final class AmmCrossParticipantIntegrationTestDefault
    extends AmmCrossParticipantIntegrationTest {
  registerPlugin(new UseBftSequencer(loggerFactory))
}
