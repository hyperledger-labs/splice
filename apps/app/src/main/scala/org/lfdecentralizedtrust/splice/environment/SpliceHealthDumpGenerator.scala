// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.admin.api.client.data.CantonStatus
import com.digitalasset.canton.console.{GrpcAdminCommandRunner, HealthDumpGenerator}
import com.digitalasset.canton.logging.pretty.Pretty
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

import scala.annotation.nowarn

// TODO(tech-debt): named SpliceStatus as there already exists a org.lfdecentralizedtrust.splice.environment.SpliceStatus -- figure out how to name this better, or merge these two classes
case class SpliceStatus2() extends CantonStatus {
  override def pretty: Pretty[SpliceStatus2.this.type] =
    Pretty.prettyOfString(_ => "Not implemented")
}

@nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
class SpliceHealthDumpGenerator(
    override val environment: SpliceEnvironment,
    override val grpcAdminCommandRunner: GrpcAdminCommandRunner,
) extends HealthDumpGenerator[SpliceStatus2] {
  override protected implicit val statusEncoder: Encoder[SpliceStatus2] = {
    deriveEncoder[SpliceStatus2]
  }

  override def status(): SpliceStatus2 = {
    SpliceStatus2()
  }
}
