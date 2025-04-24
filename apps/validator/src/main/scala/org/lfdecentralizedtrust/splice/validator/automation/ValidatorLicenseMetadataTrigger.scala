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
import org.lfdecentralizedtrust.splice.environment.{
  BuildInfo,
  PackageVersionSupport,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.PackageVersionSupport.FeatureSupport

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class ValidatorLicenseMetadataTrigger(
    override protected val context: TriggerContext,
    connection: SpliceLedgerConnection,
    store: ValidatorStore,
    contactPoint: String,
    packageVersionSupport: PackageVersionSupport,
)(implicit override val ec: ExecutionContext, override val tracer: Tracer, mat: Materializer)
    extends ScheduledTaskTrigger[ValidatorLicenseMetadataTrigger.Task] {

  private val validator = store.key.validatorParty

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[ValidatorLicenseMetadataTrigger.Task]] =
    for {
      validatorLicenseMetadataFeatureSupport <- packageVersionSupport
        .supportsValidatorLicenseMetadata(
          Seq(validator, store.key.dsoParty),
          now,
        )
      tasks <-
        if (validatorLicenseMetadataFeatureSupport.supported) {
          for {
            licenseO <- store
              .lookupValidatorLicenseWithOffset()
              .map(_.value.flatMap(_.toAssignedContract))
          } yield {
            licenseO.toList
              .filter(license =>
                license.payload.metadata.toScala.fold(true)(metadata =>
                  (metadata.version != BuildInfo.compiledVersion || metadata.contactPoint != contactPoint) &&
                    (now - CantonTimestamp.tryFromInstant(metadata.lastUpdatedAt))
                      .compareTo(ValidatorLicenseMetadataTrigger.minMetadataUpdateInterval) > 0
                )
              )
              .map(license =>
                ValidatorLicenseMetadataTrigger.Task(
                  BuildInfo.compiledVersion,
                  contactPoint,
                  license,
                  validatorLicenseMetadataFeatureSupport,
                )
              )
          }
        } else {
          Future.successful(Seq.empty)
        }
    } yield tasks

  override def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[ValidatorLicenseMetadataTrigger.Task]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    connection
      .submit(
        actAs = Seq(validator),
        readAs = Seq.empty,
        update = task.work.existingLicense.exercise(
          _.exerciseValidatorLicense_UpdateMetadata(
            BuildInfo.compiledVersion,
            contactPoint,
          )
        ),
      )
      .noDedup
      .withPrefferedPackage(task.work.featureSupport.packageIds)
      .yieldUnit()
      .map(_ =>
        TaskSuccess(
          s"Updated ValidatorLicense to version ${BuildInfo.compiledVersion} and contact point $contactPoint"
        )
      )

  override def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[ValidatorLicenseMetadataTrigger.Task]
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractByIdOnDomain(ValidatorLicense.COMPANION)(
        task.work.existingLicense.domain,
        task.work.existingLicense.contractId,
      )
      .map(_.isEmpty)
}

object ValidatorLicenseMetadataTrigger {

  private val minMetadataUpdateInterval = java.time.Duration.ofHours(1)

  final case class Task(
      targetVersion: String,
      targetContactPoint: String,
      existingLicense: AssignedContract[ValidatorLicense.ContractId, ValidatorLicense],
      featureSupport: FeatureSupport,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("targetVersion", _.targetVersion.doubleQuoted),
        param("targetContactPoint", _.targetContactPoint.doubleQuoted),
        param("existingLicense", _.existingLicense),
        param("featureSupport", _.featureSupport),
      )
    }
  }
}
