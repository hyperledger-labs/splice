// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.environment.{PackageVersionSupport, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class ValidatorLicenseActivityTrigger(
    override protected val context: TriggerContext,
    connection: SpliceLedgerConnection,
    store: ValidatorStore,
    packageVersionSupport: PackageVersionSupport,
)(implicit override val ec: ExecutionContext, override val tracer: Tracer, mat: Materializer)
    extends ScheduledTaskTrigger[ValidatorLicenseActivityTrigger.Task] {

  private val validator = store.key.validatorParty

  override def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[ValidatorLicenseActivityTrigger.Task]] =
    for {
      supportsValidatorLicenseActivity <- packageVersionSupport.supportsValidatorLicenseActivity(
        now
      )
      tasks <-
        if (supportsValidatorLicenseActivity) {
          for {
            licenseO <- store
              .lookupValidatorLicenseWithOffset()
              .map(_.value.flatMap(_.toAssignedContract))
          } yield {
            licenseO.toList
              .filter(license =>
                license.payload.lastActiveAt.toScala.fold(true)(lastActiveAt =>
                  (now - CantonTimestamp.tryFromInstant(lastActiveAt))
                    .compareTo(ValidatorLicenseActivityTrigger.activityReportMinInterval) > 0
                )
              )
              .map(license =>
                ValidatorLicenseActivityTrigger.Task(
                  license
                )
              )
          }
        } else {
          Future.successful(Seq.empty)
        }
    } yield tasks

  override def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[ValidatorLicenseActivityTrigger.Task]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    connection
      .submit(
        actAs = Seq(validator),
        readAs = Seq.empty,
        update = task.work.existingLicense.exercise(
          _.exerciseValidatorLicense_ReportActive(
          )
        ),
      )
      .noDedup
      .yieldUnit()
      .map(_ =>
        TaskSuccess(
          s"Reported ValidatorLicense activity"
        )
      )

  override def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[ValidatorLicenseActivityTrigger.Task]
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractByIdOnDomain(ValidatorLicense.COMPANION)(
        task.work.existingLicense.domain,
        task.work.existingLicense.contractId,
      )
      .map(_.isEmpty)
}

object ValidatorLicenseActivityTrigger {

  private val activityReportMinInterval = java.time.Duration.ofHours(1)

  final case class Task(
      existingLicense: AssignedContract[ValidatorLicense.ContractId, ValidatorLicense]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("existingLicense", _.existingLicense)
      )
    }
  }
}
