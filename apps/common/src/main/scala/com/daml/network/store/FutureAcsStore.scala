package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.util.JavaContract
import com.daml.ledger.javaapi.data.codegen.{
  ContractCompanion,
  DamlRecord,
  InterfaceCompanion,
  ContractId,
  Contract,
}
import com.daml.ledger.javaapi.data.Template
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

// TODO (#2619) remove no-arg acs from CoinAppStore, and this
private[store] final class FutureAcsStore(underlying: Future[AcsStore])(implicit
    ec: ExecutionContext
) extends AcsStore {

  override def lookupContractById[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(id: ContractId[T]) = underlying.flatMap(_.lookupContractById(templateCompanion)(id))

  override def lookupContractById[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(id: Id) = underlying.flatMap(_.lookupContractById(interfaceCompanion)(id))

  override def findContractWithOffset[
      TC <: Contract[TCid, T],
      TCid <: ContractId[T],
      T <: Template,
  ](templateCompanion: ContractCompanion[TC, TCid, T])(p: JavaContract[TCid, T] => Boolean) =
    underlying.flatMap(_.findContractWithOffset(templateCompanion)(p))

  override def findContractWithOffset[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(p: JavaContract[Id, View] => Boolean) =
    underlying.flatMap(_.findContractWithOffset(interfaceCompanion)(p))

  override def listContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T],
      filter: JavaContract[TCid, T] => Boolean,
      limit: Option[Long],
  ) = underlying.flatMap(_.listContracts(templateCompanion, filter, limit))

  override def listContractsI[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View],
      filter: JavaContract[Id, View] => Boolean,
      limit: Option[Long],
  ) = underlying.flatMap(_.listContractsI(interfaceCompanion, filter, limit))

  override def streamContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  ) = futureSource(underlying.map(_.streamContracts(templateCompanion)))

  override def streamContracts[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  ) = futureSource(underlying.map(_.streamContracts(interfaceCompanion)))

  override def signalWhenIngested[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  ) = underlying.flatMap(_.signalWhenIngested(templateCompanion))

  override def signalWhenIngested(offset: String)(implicit tc: TraceContext) =
    underlying.flatMap(_.signalWhenIngested(offset))

  override def close(): Unit = underlying.foreach(_.close())

  private[this] def futureSource[A](s: Future[Source[A, NotUsed]]): Source[A, NotUsed] =
    Source futureSource s mapMaterializedValue (_ => NotUsed)
}
