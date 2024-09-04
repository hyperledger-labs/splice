// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store

case class ResultsPage[R](resultsInPage: Seq[R], nextPageToken: Option[Long]) {
  def mapResultsInPage[M](f: R => M): ResultsPage[M] =
    ResultsPage(resultsInPage.map(f), nextPageToken)
}
