local auth0Authority = "https://canton-network-test.us.auth0.com";
local auth0ClientId = "Ob8YZSBvbZR3vsM2vGKllg3KRlRgLQSw";
local authAudience = "https://canton.network.global";
local authScope = "daml_ledger_api";
local authSecret = "test";
local testAuthSecret = "test";


local authHsUnsafe(secret) = {
  algorithm: "hs-256-unsafe",
  secret: secret,
  token_audience: authAudience,
  token_scope: authScope,
};

local authRs() = {
  algorithm: "rs-256",
  authority: auth0Authority,
  client_id: auth0ClientId,
  token_audience: authAudience,
  token_scope: authScope,
};


local auth(algorithm) =
  if (algorithm == "rs-256") then
    { auth: authRs() }
  else if (algorithm == "hs-256-unsafe") then
    { auth: authHsUnsafe(authSecret) }
  else
    error "Unknown auth algorithm" + algorithm;

local testAuth(enabled) =
  if (enabled) then
    { testAuth: authHsUnsafe(testAuthSecret) }
  else
    {};

local validatorNodes = {
  alice: {
    ledgerApi: { grpcUrl: "http://localhost:6201" },
    validator: { grpcUrl: "http://localhost:6203" },
    wallet: { grpcUrl: "http://localhost:6204", uiUrl: "http://localhost:3000" },
    splitwise: { grpcUrl: "http://localhost:6113" },
    directory: { grpcUrl: "http://localhost:6110" },
    scan: { grpcUrl: "http://localhost:6012" },
  },
  bob: {
    ledgerApi: { grpcUrl: "http://localhost:6301" },
    validator: { grpcUrl: "http://localhost:6303" },
    wallet: { grpcUrl: "http://localhost:6304", uiUrl: "http://localhost:3001" },
    splitwise: { grpcUrl: "http://localhost:6113" },
    directory: { grpcUrl: "http://localhost:6110" },
    scan: { grpcUrl: "http://localhost:6012" },
  },
};

local domainConfigs = {
  splitwise: {
    // TODO (M3-18) Change this to separate splitwise domain.
    splitwise: "global",
  },
};

local services(node) =
  if (std.objectHas(validatorNodes, node)) then
    { services: validatorNodes[node] }
  else
    error "Unknown node name " + node;

local domains(app) =
  if (std.objectHas(domainConfigs, app)) then
    { domains: domainConfigs[app] }
  else
    {};

function(
  authAlgorithm="rs-256",
  enableTestAuth,
  validatorNode,
  app,
) auth(authAlgorithm) + testAuth(std.parseJson(enableTestAuth)) + services(validatorNode) + domains(app)
