// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.data.CantonStatus
import com.digitalasset.canton.console.{GrpcAdminCommandRunner, HealthDumpGenerator}
import com.digitalasset.canton.logging.pretty.Pretty
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

import scala.annotation.nowarn

// TODO(tech-debt): named CNNodeStatus as there already exists a com.daml.network.environment.CNNodeStatus -- figure out how to name this better, or merge these two classes
case class CNNodeStatus2() extends CantonStatus {
  override def pretty: Pretty[CNNodeStatus2.this.type] =
    Pretty.prettyOfString(_ => "Not implemented")
}

// TODO(#1159): Properly implement or remove health dumping
@nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
class CNNodeHealthDumpGenerator(
    override val environment: CNNodeEnvironment,
    override val grpcAdminCommandRunner: GrpcAdminCommandRunner,
) extends HealthDumpGenerator[CNNodeStatus2] {
  override protected implicit val statusEncoder: Encoder[CNNodeStatus2] = {
    deriveEncoder[CNNodeStatus2]
  }

  override def status(): CNNodeStatus2 = {
    CNNodeStatus2()
  }
}
