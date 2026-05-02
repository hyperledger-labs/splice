// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { loadDripConfig } from "../src/config.js";

describe("loadDripConfig", () => {
  const REQUIRED_ENV = {
    PARTICIPANT_LEDGER_API: "http://localhost:2975",
    LEDGER_ACCESS_TOKEN: "test-token",
    VALIDATOR_API_URL: "http://localhost:2903/api/validator",
    VALIDATOR_ACCESS_TOKEN: "test-validator-token",
    ADMIN_USER: "ledger-api-user",
    SYNCHRONIZER_ID: "sync::1220abc",
    SENDER_PARTY_ID: "sender::1220abc",
    INSTRUMENT_ADMIN: "dso::1220abc",
  };

  let savedEnv: Record<string, string | undefined>;

  beforeEach(() => {
    savedEnv = { ...process.env };
    Object.entries(REQUIRED_ENV).forEach(([k, v]) => {
      process.env[k] = v;
    });
  });

  afterEach(() => {
    process.env = savedEnv;
  });

  it("loads required env vars into config", () => {
    const cfg = loadDripConfig();
    expect(cfg.participantLedgerApi).toBe("http://localhost:2975");
    expect(cfg.ledgerAccessToken).toBe("test-token");
    expect(cfg.validatorApiUrl).toBe("http://localhost:2903/api/validator");
    expect(cfg.senderPartyId).toBe("sender::1220abc");
    expect(cfg.instrumentAdmin).toBe("dso::1220abc");
  });

  it("uses defaults for optional fields", () => {
    const cfg = loadDripConfig();
    expect(cfg.instrumentId).toBe("Amulet");
    expect(cfg.dripAmount).toBe("1.0");
    expect(cfg.delayBetweenRowsMs).toBe(300);
    expect(cfg.metaReason).toBe("Amulet distribution");
  });

  it("allows overriding optional fields", () => {
    process.env.INSTRUMENT_ID = "CustomToken";
    process.env.DRIP_AMOUNT = "5.0";
    process.env.DRIP_DELAY_MS = "1000";
    process.env.DRIP_META_REASON = "Welcome bonus";
    const cfg = loadDripConfig();
    expect(cfg.instrumentId).toBe("CustomToken");
    expect(cfg.dripAmount).toBe("5.0");
    expect(cfg.delayBetweenRowsMs).toBe(1000);
    expect(cfg.metaReason).toBe("Welcome bonus");
  });

  it("throws when required env var is missing", () => {
    delete process.env.PARTICIPANT_LEDGER_API;
    expect(() => loadDripConfig()).toThrow();
  });
});
