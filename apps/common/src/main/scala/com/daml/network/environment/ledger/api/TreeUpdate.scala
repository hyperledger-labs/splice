package com.daml.network.environment.ledger.api

import com.daml.ledger.javaapi.data.TransactionTree

sealed abstract class TreeUpdate extends Product with Serializable

final case class TransactionTreeUpdate(tree: TransactionTree) extends TreeUpdate

final case class ReassignmentUpdate(transfer: Reassignment[ReassignmentEvent]) extends TreeUpdate
