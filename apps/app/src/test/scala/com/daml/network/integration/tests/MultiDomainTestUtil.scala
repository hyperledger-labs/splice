package com.daml.network.util

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.protocol.LfContractId
import org.scalactic.source
import org.scalatest.prop.TableDrivenPropertyChecks.forEvery as tForEvery

trait MultiDomainTestUtil extends CNNodeTestCommon {
  def assertAllOn(
      onDomain: DomainAlias
  )(cids: ContractId[?]*)(implicit env: CNNodeTestConsoleEnvironment, pos: source.Position) = {
    val lfIds = cids.map(c => c: LfContractId)
    val domains = providerSplitwellBackend.participantClient.transfer.lookup_contract_domain(
      lfIds: _*
    )
    tForEvery(Table("contractId", lfIds: _*)) { lfId =>
      domains.get(lfId) shouldBe Some(onDomain.unwrap)
    }
  }
}
