// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

object EventId {

  /*
   * The scan update history endpoint v1 removed the leading `#` from the event id.
   * This is used to build the responses for the new endpoint.
   * */
  def noPrefixFromUpdateIdAndNodeId(updateId: String, nodeId: Int): String = {
    s"$updateId:$nodeId"
  }

  /* Prior to canton 3.3, where the event id was removed, canton was exposing the event id in the format
   * `#updateId:nodeId`. In our own dbs we were storing it raw and this adds the ability to preserve the same
   * format for backwards compatibility.
   * */
  def prefixedFromUpdateIdAndNodeId(updateId: String, nodeId: Int): String = {
    s"#$updateId:$nodeId"
  }

  def updateIdFromEventId(eventId: String): String = {
    splitEventIdOrFail(eventId)._1
  }

  def nodeIdFromEventId(eventId: String): Int = {
    splitEventIdOrFail(eventId)._2
  }

  private def splitEventIdOrFail(eventId: String): (String, Int) = {
    eventId.split(":") match {
      case Array(updateId, nodeId) => (updateId.drop(1) /*drop leading '#' */, nodeId.toInt)
      case _ => throw new IllegalArgumentException(s"Invalid eventId format: $eventId")
    }

  }

  // TODO(#640) - remove this conversion as it's costly
  def lastDescendedNodeFromChildNodeIds(
      nodeId: Int,
      nodesWithChildren: Map[Int, Seq[Int]],
  ): Int =
    nodesWithChildren
      .getOrElse(nodeId, Seq.empty)
      .maxOption
      .map(lastChildId => lastDescendedNodeFromChildNodeIds(lastChildId, nodesWithChildren))
      .getOrElse(nodeId)

}
