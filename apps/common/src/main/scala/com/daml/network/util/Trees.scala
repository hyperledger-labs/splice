// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, TransactionTree, TreeEvent}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

object Trees {

  type StackElement = (TreeEvent, Seq[TreeEvent])

  @SuppressWarnings(Array("org.wartremover.warts.While", "org.wartremover.warts.Var"))
  def foldTree[State](
      tree: TransactionTree,
      initialState: State,
  )(
      onCreate: (State, CreatedEvent, Seq[TreeEvent]) => State,
      onExercise: (State, ExercisedEvent, Seq[TreeEvent]) => State,
  ): State = {
    var state = initialState
    val stack: mutable.Stack[StackElement] = mutable.Stack()
    val roots = tree.getRootEventIds.asScala.map(id => tree.getEventsById.get(id))
    stack.pushAll(roots.map((_, Seq())).reverse)
    while (stack.nonEmpty) {
      val (node, pathToNode) = stack.pop()
      node match {
        case created: CreatedEvent =>
          state = onCreate(state, created, pathToNode)
        case exercised: ExercisedEvent =>
          state = onExercise(state, exercised, pathToNode)
          val children = exercised.getChildEventIds.asScala.map(tree.getEventsById.get(_))
          stack.pushAll(children.map((_, pathToNode :+ node)).reverse)
        case _ =>
      }
    }
    state
  }
}
