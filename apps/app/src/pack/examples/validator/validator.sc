println("Starting all first-party apps of the self-hosted validator: " + amuletNodes)
amuletNodes.local.foreach(_.startSync())
