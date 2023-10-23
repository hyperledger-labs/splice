package com.daml.network
package unit.store

import store.MultiDomainAcsStore.ContractFilter

import com.daml.ledger.javaapi.data.codegen.ContractTypeCompanion
import com.daml.network.util.QualifiedName
import com.digitalasset.canton.topology.PartyId

import org.scalatest.AppendedClues
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

/** A static-ish check that every template registered with a store's contract
  * filter in some app also has a declared global domain migration strategy.
  * If your PR fails this, you have probably added a new template without also
  * declaring that strategy.
  */
class GlobalDomainMigrationCoverageTest
    extends AnyWordSpec
    with AppendedClues
    with Matchers
    with TableDrivenPropertyChecks {
  import GlobalDomainMigrationCoverageTest.*

  filtersAndMoveLists.foreach { case (label, filtered, rawHandled) =>
    s"${label.getClass.getSimpleName}" should {
      val handled = rawHandled.view.map(t => QualifiedName(t.TEMPLATE_ID)).toSet

      "handle every listened-to contract type not handled elsewhere" in {
        filtered.ingestionFilter.templateIds should contain allElementsOf handled
      }

      "either handle or not handle each template" in {
        forEvery(Table(("template", "reason"), knownNotHandled.toSeq: _*)) { (templateId, reason) =>
          handled shouldNot contain(templateId) withClue reason
        }
        // ^ does part of v but with better error messages
        handled should contain noElementsOf knownNotHandled.keySet
      }
    }
  }

  "templates" should {
    "have no duplicate handlers across automation services" in {
      val overlappingAutomationStoresById = filtersAndMoveLists
        .flatMap { case (id, _, handled) =>
          handled.view.map(id -> _)
        }
        .groupMap(_._2.TEMPLATE_ID)(_._1.getClass.getSimpleName)
        .filter(_._2.sizeIs > 1)
      overlappingAutomationStoresById shouldBe empty
    }
  }

  "knownNotHandled" should {
    val allHandled = filtersAndMoveLists.view
      .flatMap(_._3.map(i => QualifiedName(i.TEMPLATE_ID)))
      .toSet

    "not list any handled contracts" in {
      knownNotHandled.keySet intersect allHandled shouldBe empty
    }

    "list all unhandled contracts" in {
      filtersAndMoveLists
        .flatMap { case (label, filter, _) =>
          (filter.ingestionFilter.templateIds diff allHandled diff knownNotHandled.keySet).view
            .map(label -> _)
        }
        .groupMap(_._2)(
          _._1.getClass.getSimpleName
        ) shouldBe empty withClue "<- candidate handling stores in the map"
    }
  }
}

object GlobalDomainMigrationCoverageTest {
  import sv.store.{SvStore, SvSvStore, SvSvcStore}
  import validator.store.ValidatorStore

  private val dummyParty = PartyId.tryFromProtoPrimitive("foo::dummy")

  private val filtersAndMoveLists
      : Seq[(Object, ContractFilter, Seq[ContractTypeCompanion[?, ?, ?, ?]])] =
    Seq(
      (
        ValidatorStore,
        ValidatorStore.contractFilter(
          ValidatorStore.Key(
            svcParty = dummyParty,
            validatorParty = dummyParty,
          )
        ),
        ValidatorStore.templatesMovedByMyAutomation,
      ),
      (
        SvSvcStore,
        SvSvcStore.contractFilter(dummyParty, dummyParty),
        SvSvcStore.templatesMovedByMyAutomation,
      ),
      (
        SvSvStore,
        SvSvStore.contractFilter(SvStore.Key(dummyParty, dummyParty)),
        SvSvStore.templatesMovedByMyAutomation,
      ),
    )

  // How do we ensure that new templates get migration added somewhere?  If
  // they are queried in an app, they need to be added to *some* ingestion
  // filter, so the author will naturally pick one.  The idea is that we
  // compare
  //
  //  1. the list the system effectively forces everyone to update, the
  //     ingestion filters, to
  //  2. the lists that are *not* checked except at domain migration time,
  //
  // and (hopefully) forestall overlooked required changes that way.

  private val todoCnsDirectory = ("TODO (#5959) make directory/cns contracts follow CoinRules;"
    + " global domain migration will break the cns if this is not done")

  // "not handled" usually means "handled by a separate automation"
  private val knownNotHandled = {
    import codegen.java.cc.{coin, coinimport, globaldomain}
    import codegen.java.cn.{cns as cnsCodegen, directory as directoryCodegen, svc}
    import codegen.java.cn.wallet.{subscriptions as subsCodegen, topupstate as topUpCodegen}
    Seq(
      cnsCodegen.CnsEntry.COMPANION -> todoCnsDirectory,
      cnsCodegen.CnsEntryContext.COMPANION -> todoCnsDirectory,
      cnsCodegen.CnsRules.COMPANION -> todoCnsDirectory,
      directoryCodegen.DirectoryEntry.COMPANION -> todoCnsDirectory,
      directoryCodegen.DirectoryEntryContext.COMPANION -> todoCnsDirectory,
      directoryCodegen.DirectoryInstall.COMPANION -> todoCnsDirectory,
      directoryCodegen.DirectoryInstallRequest.COMPANION -> todoCnsDirectory,
      subsCodegen.SubscriptionInitialPayment.COMPANION -> todoCnsDirectory,
      subsCodegen.SubscriptionIdleState.COMPANION -> todoCnsDirectory,
      subsCodegen.SubscriptionPayment.COMPANION -> todoCnsDirectory,
      subsCodegen.TerminatedSubscription.COMPANION -> todoCnsDirectory,
      globaldomain.MemberTraffic.COMPANION -> "tied to a specific domainId, never migrated",
      topUpCodegen.ValidatorTopUpState.COMPANION -> "tied to a specific domainId, never migrated",
      coinimport.ImportCrate.COMPANION -> "TODO (#7822) follow CoinRules in SvSvc",
      coin.AppRewardCoupon.COMPANION -> "TODO (#7822) follow CoinRules in UserWallet",
      coin.LockedCoin.COMPANION -> "TODO (#7822) follow CoinRules in UserWallet",
      coin.ValidatorRewardCoupon.COMPANION -> "TODO (#7822) follow CoinRules in UserWallet",
      svc.coinprice.CoinPriceVote.COMPANION -> "TODO (#7822) follow CoinRules in SvSvc",
    ).view.map { case (c, reason) =>
      (QualifiedName(c.TEMPLATE_ID), reason)
    }.toMap
  }
}
