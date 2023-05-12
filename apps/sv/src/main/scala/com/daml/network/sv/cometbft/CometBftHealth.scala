package com.daml.network.sv.cometbft

sealed trait CometBftHealth

case object HealthyCometBftNode extends CometBftHealth
final case class UnhealthyCometBftNode(httpStatus: Int) extends CometBftHealth
