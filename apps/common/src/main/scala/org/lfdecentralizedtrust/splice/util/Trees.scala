// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Transaction, Event}

import scala.annotation.tailrec
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
  def getLocalEventIndices(
      tree: Transaction
  ): Map[Int, Int] = {
    val eventsById = tree.getEventsById.asScala
    @tailrec
    def makeEventIdToNumber(
        pending: List[Event],
        acc: Map[Int, Int],
    ): Map[Int, Int] = {
      pending match {
        case Nil =>
          acc
        case head :: tail =>
          head match {
            case created: CreatedEvent =>
              makeEventIdToNumber(tail, acc + (created.getNodeId.intValue() -> acc.size))
            case exercised: ExercisedEvent =>
              makeEventIdToNumber(
                tree.getChildNodeIds(exercised).asScala.map(eventsById).toList ++ tail,
                acc + (exercised.getNodeId.intValue() -> acc.size),
              )
            case _ => sys.error(s"Unexpected event type: $head")
          }
      }
    }
    makeEventIdToNumber(
      tree.getRootNodeIds.asScala.map(eventsById).toList,
      Map.empty,
    )
  }

}
