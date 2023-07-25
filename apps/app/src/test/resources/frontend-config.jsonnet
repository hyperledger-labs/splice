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
  else if (algorithm == "none") then
    {}
  else
    error "Unknown auth algorithm" + algorithm;

local testAuth(enabled) =
  if (enabled) then
    { testAuth: authHsUnsafe(testAuthSecret) }
  else
    {};

local validatorNodes(clusterProtocol, clusterAddress, port) = {
  alice: {
    jsonApiBackend: { url: "http://localhost:6201" },
    jsonApi: { url: "http://localhost:" + port + "/api/json-api/" },
    participantAdmin: { url: "http://localhost:6202" },
    validator: { url: "http://localhost:5203" },
    wallet: { url: "http://localhost:5203", uiUrl: "http://localhost:3000" },
    splitwell: { url: "http://localhost:5113" },
    directory: { url: "http://localhost:5110" },
    scan: { url: "http://localhost:5012" },
  },
  bob: {
    jsonApiBackend: { url: "http://localhost:6301" },
    jsonApi: { url: "http://localhost:" + port + "/api/json-api/" },
    participantAdmin: { url: "http://localhost:6302" },
    validator: { url: "http://localhost:5303" },
    wallet: { url: "http://localhost:5303", uiUrl: "http://localhost:3001" },
    splitwell: { url: "http://localhost:5113" },
    directory: { url: "http://localhost:5110" },
    scan: { url: "http://localhost:5012" },
  },
  splitwell: {
    validator: { url: "http://localhost:5403" },
  },
  preflight: {
    jsonApiBackend: { url: "http://localhost:7575" },
    jsonApi: { url: "http://localhost:" + port + "/api/json-api/" },
    participantAdmin: { url: "http://localhost:8002" },
    validator: { url: "http://localhost:5003" },
    wallet: { url: "http://localhost:5003", uiUrl: "http://localhost:3000" },
    splitwell: { url: "http://localhost:5113" },
    directory: { url: clusterProtocol + "://" + "directory.sv-1.svc." + clusterAddress + "/api/v0/directory" },
    scan: { url: clusterProtocol + "://" + "scan.sv-1.svc." + clusterAddress + "/api/v0/scan" },
  },
  scan: {
    scan: { url: "http://localhost:5012" },
  },
  sv1: {
    sv: { url: "http://localhost:5014" },
    validator: { url: "http://localhost:5003" },
    directory: { url: "http://localhost:5110" },
    scan: { url: "http://localhost:5012" },
  },
  sv2: {
    sv: { url: "http://localhost:5614" },
    validator: { url: "http://localhost:5003" },
    directory: { url: "http://localhost:5110" },
    scan: { url: "http://localhost:5012" },
  },
};

local services(node, clusterProtocol, clusterAddress, port) =
  if (std.objectHas(validatorNodes(clusterProtocol, clusterAddress, port), node)) then
    { services: validatorNodes(clusterProtocol, clusterAddress, port)[node] }
  else
    error "Unknown node name " + node;

function(
  authAlgorithm="rs-256",
  enableTestAuth,
  validatorNode,
  app,
  clusterProtocol,
  clusterAddress,
  port,
) auth(authAlgorithm) + testAuth(std.parseJson(enableTestAuth)) + services(validatorNode, clusterProtocol, clusterAddress, port)
