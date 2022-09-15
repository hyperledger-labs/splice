// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.api.v1.transaction.TreeEvent.Kind.{Created, Empty, Exercised}
import com.daml.ledger.api.v1.transaction.{TransactionTree, TreeEvent}
import scala.collection.mutable

object Trees {

  type StackElement = (TreeEvent.Kind, Seq[TreeEvent.Kind])

  @SuppressWarnings(Array("org.wartremover.warts.While"))
  def traverseTree(
      tree: TransactionTree,
      onCreate: (Created, Seq[TreeEvent.Kind]) => Unit,
      onExercise: (Exercised, Seq[TreeEvent.Kind]) => Unit,
  ): Unit = {
    val stack: mutable.Stack[StackElement] = mutable.Stack()
    val roots = tree.rootEventIds.map(id => tree.eventsById(id).kind)
    stack.pushAll(roots.map((_, Seq())).reverse)
    while (stack.nonEmpty) {
      val (node, pathToNode) = stack.pop()
      node match {
        case Empty =>
        case created: Created => onCreate(created, pathToNode)
        case exercised: Exercised =>
          onExercise(exercised, pathToNode)
          val children = exercised.value.childEventIds.map(tree.eventsById(_).kind)
          stack.pushAll(children.map((_, pathToNode :+ node)).reverse)
      }
    }
  }
}
