package com.daml.network.store

import com.daml.network.history.CoinTransaction

import scala.concurrent.Future

trait CCHistoryStore extends AutoCloseable {
  // TODO(#300): eventually this probably needs a start & end offset - related to #300
  def getCCHistory: Future[Seq[CoinTransaction]]

  def addTransaction(transaction: CoinTransaction): Future[Unit]

}
