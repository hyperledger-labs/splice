// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.automation

import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.splice.validatorlicense.ValidatorLicense
import com.daml.network.environment.{SpliceLedgerConnection, PackageIdResolver}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.util.AssignedContract
import com.daml.network.validator.store.ValidatorStore
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
    scanConnection: BftScanConnection,
)(implicit override val ec: ExecutionContext, override val tracer: Tracer, mat: Materializer)
    extends ScheduledTaskTrigger[ValidatorLicenseActivityTrigger.Task] {

  private val validator = store.key.validatorParty

  override def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[ValidatorLicenseActivityTrigger.Task]] =
    for {
      amuletRules <- scanConnection.getAmuletRules()
      supportsValidatorLicenseActivity = PackageIdResolver.supportsValidatorLicenseActivity(
        now,
        amuletRules.payload,
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
