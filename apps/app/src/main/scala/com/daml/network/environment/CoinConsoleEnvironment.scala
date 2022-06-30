package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.data.CommunityCantonStatus
import com.digitalasset.canton.console.{
  CantonHealthAdministration,
  CommunityLocalDomainReference,
  CommunityRemoteDomainReference,
  ConsoleEnvironment,
  ConsoleGrpcAdminCommandRunner,
  ConsoleOutput,
  DomainReference,
  FeatureFlag,
  Help,
  LocalInstanceReference,
  NodeReferences,
  StandardConsoleOutput,
}

class CoinConsoleEnvironment(
    val environment: CoinEnvironmentImpl,
    val consoleOutput: ConsoleOutput = StandardConsoleOutput,
    protected val createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner =
      new ConsoleGrpcAdminCommandRunner(_),
) extends ConsoleEnvironment // TODO(Arne): Generalize this.
    {

  override type Env = CoinEnvironmentImpl
  override type DomainLocalRef = CommunityLocalDomainReference
  override type DomainRemoteRef = CommunityRemoteDomainReference
  override type Status = CommunityCantonStatus

  override def health: CantonHealthAdministration[CommunityCantonStatus] = ???

  override protected def startupOrderPrecedence(instance: LocalInstanceReference): Int = ???

  override protected def localDomainHelpItems(
      scope: Set[FeatureFlag],
      localDomain: CommunityLocalDomainReference,
  ): Seq[Help.Item] = ???

  override protected def remoteDomainHelpItems(
      scope: Set[FeatureFlag],
      remoteDomain: CommunityRemoteDomainReference,
  ): Seq[Help.Item] = ???

  override protected def domainsTopLevelValue(
      h: TopLevelValue.Partial,
      domains: NodeReferences[
        DomainReference,
        CommunityRemoteDomainReference,
        CommunityLocalDomainReference,
      ],
  ): TopLevelValue[
    NodeReferences[DomainReference, CommunityRemoteDomainReference, CommunityLocalDomainReference]
  ] = ???

  override protected def localDomainTopLevelValue(
      h: TopLevelValue.Partial,
      d: CommunityLocalDomainReference,
  ): TopLevelValue[CommunityLocalDomainReference] = ???

  override protected def remoteDomainTopLevelValue(
      h: TopLevelValue.Partial,
      d: CommunityRemoteDomainReference,
  ): TopLevelValue[CommunityRemoteDomainReference] = ???

  override protected def createDomainReference(name: String): CommunityLocalDomainReference = ???

  override protected def createRemoteDomainReference(name: String): CommunityRemoteDomainReference =
    ???
}
