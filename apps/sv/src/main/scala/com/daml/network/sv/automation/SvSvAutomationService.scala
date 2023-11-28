package com.daml.network.sv.automation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{AssignTrigger, CNNodeAppAutomationService}
import com.daml.network.codegen.java.cn.svlocal.approvedsvidentity.ApprovedSvIdentity
import com.daml.network.environment.{CNLedgerClient, PackageIdResolver, RetryProvider}
import com.daml.network.sv.automation.singlesv.ExpireValidatorOnboardingTrigger
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.util.QualifiedName
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class SvSvAutomationService(
    clock: Clock,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    ledgerClient: CNLedgerClient,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      config.automation,
      clock,
      svStore,
      PackageIdResolver
        .inferFromCoinRules(
          clock,
          svcStore,
          loggerFactory,
          SvSvAutomationService.extraPackageIdResolver,
        ),
      ledgerClient,
      retryProvider,
    ) {
  registerTrigger(new ExpireValidatorOnboardingTrigger(triggerContext, svStore, connection))
  registerTrigger(new AssignTrigger(triggerContext, svStore, connection, store.key.svParty))
}

object SvSvAutomationService {
  private[automation] def extraPackageIdResolver(template: QualifiedName): Option[String] =
    // For SV local state, we can just use whatever version we want.
    Option.when(template == QualifiedName(ApprovedSvIdentity.TEMPLATE_ID))(
      ApprovedSvIdentity.TEMPLATE_ID.getPackageId
    )
}
