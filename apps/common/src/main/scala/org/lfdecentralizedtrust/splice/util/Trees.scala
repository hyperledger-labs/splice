// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Transaction, Event}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

object Trees {

  type StackElement = (Event, Seq[Event])

  @SuppressWarnings(Array("org.wartremover.warts.While", "org.wartremover.warts.Var"))
  def foldTree[State](
      tree: Transaction,
      initialState: State,
  )(
      onCreate: (State, CreatedEvent, Seq[Event]) => State,
      onExercise: (State, ExercisedEvent, Seq[Event]) => State,
  ): State = {
    var state = initialState
    val stack: mutable.Stack[StackElement] = mutable.Stack()
    val roots = tree.getRootNodeIds.asScala.map(id => tree.getEventsById.get(id))
    stack.pushAll(roots.map((_, Seq())).reverse)
    while (stack.nonEmpty) {
      val (node, pathToNode) = stack.pop()
      node match {
        case created: CreatedEvent =>
          state = onCreate(state, created, pathToNode)
        case exercised: ExercisedEvent =>
          state = onExercise(state, exercised, pathToNode)
          val children = tree.getChildNodeIds(exercised).asScala.map(tree.getEventsById.get(_))
          stack.pushAll(children.map((_, pathToNode :+ node)).reverse)
        case _ =>
      }
    }
    state
  }

  /** Returns a map that maps event ids to consecutive numbers, assigned by in-order traversing the transaction tree */
  @SuppressWarnings(Array("org.wartremover.warts.While", "org.wartremover.warts.Var"))
  def getLocalEventIndices(
      tree: Transaction
  ): Map[Int, Int] = {
    val eventsById = tree.getEventsById
    val size = eventsById.size
    // Iterative in-order traversal using a pre-sized array-backed stack.
    val out = new mutable.HashMap[Int, Int](size, 0.75)
    val stack = new mutable.ArrayDeque[Event](math.max(16, size))
    // Push roots in reverse so the leftmost root is popped first.
    val roots = tree.getRootNodeIds
    var i = roots.size() - 1
    while (i >= 0) {
      stack.append(eventsById.get(roots.get(i)))
      i -= 1
    }
    var next = 0
    while (stack.nonEmpty) {
      val node = stack.removeLast()
      node match {
        case created: CreatedEvent =>
          out.update(created.getNodeId.intValue(), next)
          next += 1
        case exercised: ExercisedEvent =>
          out.update(exercised.getNodeId.intValue(), next)
          next += 1
          // Push children in reverse to preserve left-to-right in-order traversal.
          val children = tree.getChildNodeIds(exercised)
          var j = children.size() - 1
          while (j >= 0) {
            stack.append(eventsById.get(children.get(j)))
            j -= 1
          }
        case other => sys.error(s"Unexpected event type: $other")
      }
    }
    out.toMap
  }

}
