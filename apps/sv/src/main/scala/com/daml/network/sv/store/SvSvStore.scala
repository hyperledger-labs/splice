package com.daml.network.sv.store

import com.daml.network.codegen.java.cn.validatoronboarding as vo
import com.daml.network.store.{AcsStore, CoinAppStoreWithoutHistory}
import com.daml.network.sv.store.memory.InMemorySvSvStore
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}

import scala.concurrent.{ExecutionContext, Future}

/* Store used by the SV app for filtering contracts visible to the SV party. */
trait SvSvStore extends CoinAppStoreWithoutHistory {

  def listValidatorOnboardings()
      : Future[Seq[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]]] =
    acs.listContracts(vo.ValidatorOnboarding.COMPANION)

  def key: SvStore.Key
}

object SvSvStore {
  def apply(
      key: SvStore.Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
  )(implicit ec: ExecutionContext): SvSvStore =
    storage match {
      case _: MemoryStorage => new InMemorySvSvStore(key, loggerFactory, futureSupervisor)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(key: SvStore.Key): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val sv = key.svParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      key.svParty,
      Map(mkFilter(vo.ValidatorOnboarding.COMPANION)(co => co.payload.sv == sv)),
    )
  }
}
