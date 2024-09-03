package com.daml.network
package unit.store

import store.MultiDomainAcsStore.ContractFilter
import com.daml.ledger.javaapi.data.codegen.ContractTypeCompanion
import com.daml.network.store.db.AcsRowData
import com.daml.network.util.PackageQualifiedName
import com.digitalasset.canton.topology.PartyId
import org.scalatest.AppendedClues
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

/** A static-ish check that every template registered with a store's contract
  * filter in some app also has a declared global domain migration strategy.
  * If your PR fails this, you have probably added a new template without also
  * declaring that strategy.
  *
  *  Grep for 'templatesMoved' to find the appropriate locations.
  */
class DecentralizedSynchronizerMigrationCoverageTest
    extends AnyWordSpec
    with AppendedClues
    with Matchers
    with TableDrivenPropertyChecks {
  import DecentralizedSynchronizerMigrationCoverageTest.*

  filtersAndMoveLists.foreach { case (label, filtered, rawHandled) =>
    s"${label.getClass.getSimpleName}" should {
      val handled = rawHandled.view.map(t => PackageQualifiedName(t.TEMPLATE_ID)).toSet

      "handle every listened-to contract type not handled elsewhere" in {
        filtered.templateIds should contain allElementsOf handled
      }

      "either handle or not handle each template" in {
        forEvery(
          Table(
            ("template", "reason"),
            knownNotHandled.view.map { case (t, (r, _)) => t -> r }.toSeq*
          )
        ) { (templateId, reason) =>
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
      .flatMap(_._3.map(i => PackageQualifiedName(i.TEMPLATE_ID)))
      .toSet

    "not list any handled contracts" in {
      knownNotHandled.keySet intersect allHandled shouldBe empty
    }

    "list all unhandled contracts" in {
      filtersAndMoveLists
        .flatMap { case (label, filter, _) =>
          (filter.templateIds diff allHandled diff knownNotHandled.keySet).view
            .map(label -> _)
        }
        .groupMap(_._2)(
          _._1.getClass.getSimpleName
        ) shouldBe empty withClue "<- candidate handling stores in the map"
    }

    "list every potential handler of each unhandled contract" in {
      filtersAndMoveLists
        .flatMap { case (label, filter, _) =>
          (filter.templateIds intersect knownNotHandled.keySet).view
            .map(label -> _)
        }
        .groupMap(_._2)(_._1.getClass.getSimpleName)
        .transform { (ingestedId, activeStoreUsers) =>
          (activeStoreUsers, knownNotHandled(ingestedId)._2.map(_.getClass.getSimpleName))
        }
        .filter { case (_, (actual, expected)) =>
          actual.toSet != expected.toSet
        } shouldBe empty withClue "<- actual candidate stores vs known-unhandling stores"
    }
  }
}

object DecentralizedSynchronizerMigrationCoverageTest {
  import scan.store.ScanStore
  import sv.store.{SvStore, SvSvStore, SvDsoStore}
  import validator.store.ValidatorStore
  import wallet.store.UserWalletStore

  private val dummyParty = PartyId.tryFromProtoPrimitive("foo::dummy")
  private val domainMigrationId = 0L

  private val filtersAndMoveLists
      : Seq[(Object, ContractFilter[_ <: AcsRowData], Seq[ContractTypeCompanion[?, ?, ?, ?]])] =
    Seq[(Object, ContractFilter[_ <: AcsRowData], Seq[ContractTypeCompanion[?, ?, ?, ?]])](
      (
        ValidatorStore,
        ValidatorStore.contractFilter(
          ValidatorStore.Key(
            dsoParty = dummyParty,
            validatorParty = dummyParty,
            appManagerEnabled = true,
          ),
          domainMigrationId,
        ),
        ValidatorStore.templatesMovedByMyAutomation(true),
      ),
      (
        SvDsoStore,
        SvDsoStore.contractFilter(dummyParty, domainMigrationId),
        SvDsoStore.templatesMovedByMyAutomation,
      ),
      (
        SvSvStore,
        SvSvStore.contractFilter(SvStore.Key(dummyParty, dummyParty)),
        SvSvStore.templatesMovedByMyAutomation,
      ),
      (
        UserWalletStore,
        UserWalletStore.contractFilter(
          UserWalletStore.Key(dummyParty, dummyParty, dummyParty),
          domainMigrationId,
        ),
        UserWalletStore.templatesMovedByMyAutomation,
      ),
      (ScanStore, ScanStore.contractFilter(ScanStore.Key(dummyParty), domainMigrationId), Seq.empty),
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

  private val knownNotHandled = {
    import codegen.java.splice.decentralizedsynchronizer
    import codegen.java.splice.wallet.topupstate as topUpCodegen
    import codegen.java.splice.wallet.buytrafficrequest as trafficRequestCodegen
    Seq(
      decentralizedsynchronizer.MemberTraffic.COMPANION ->
        reason("tied to a specific domainId, never migrated", ScanStore, SvDsoStore),
      topUpCodegen.ValidatorTopUpState.COMPANION ->
        reason("tied to a specific domainId, never migrated", ValidatorStore),
      trafficRequestCodegen.BuyTrafficRequest.COMPANION ->
        reason("tied to a specific domainId, never migrated", UserWalletStore),
    ).view.map { case (c, reason) =>
      (PackageQualifiedName(c.TEMPLATE_ID), reason)
    }.toMap
  }

  private[this] def reason(reason: String, referencingStores: Object*) = (reason, referencingStores)
}
