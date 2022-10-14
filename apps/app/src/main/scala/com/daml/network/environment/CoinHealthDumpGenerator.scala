// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.data.CantonStatus
import com.digitalasset.canton.console.{GrpcAdminCommandRunner, HealthDumpGenerator}
import com.digitalasset.canton.logging.pretty.Pretty
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

import scala.annotation.nowarn

case class CoinStatus() extends CantonStatus {
  override def pretty: Pretty[CoinStatus.this.type] =
    Pretty.prettyOfString(_ => "Not implemented")
}

// TODO(i1159): Properly implement or remove health dumping
@nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
class CoinHealthDumpGenerator(
    override val environment: CoinEnvironment,
    override val grpcAdminCommandRunner: GrpcAdminCommandRunner,
) extends HealthDumpGenerator[CoinStatus] {
  override protected implicit val statusEncoder: Encoder[CoinStatus] = {
    deriveEncoder[CoinStatus]
  }

  override def status(): CoinStatus = {
    CoinStatus()
  }
}
