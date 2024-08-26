local auth0Authority = 'https://canton-network-test.us.auth0.com';
local auth0ClientId = 'Ob8YZSBvbZR3vsM2vGKllg3KRlRgLQSw';
local authAudience = 'https://canton.network.global';
local authScope = 'daml_ledger_api';
local authSecret = 'test';
local testAuthSecret = 'test';


local authHsUnsafe(secret) = {
  algorithm: 'hs-256-unsafe',
  secret: secret,
  token_audience: authAudience,
  token_scope: authScope,
};

local authRs() = {
  algorithm: 'rs-256',
  authority: auth0Authority,
  client_id: auth0ClientId,
  token_audience: authAudience,
  token_scope: authScope,
};


local auth(algorithm) =
  if (algorithm == 'rs-256') then
    { auth: authRs() }
  else if (algorithm == 'hs-256-unsafe') then
    { auth: authHsUnsafe(authSecret) }
  else if (algorithm == 'none') then
    {}
  else
    error 'Unknown auth algorithm' + algorithm;

local testAuth(enabled) =
  if (enabled) then
    { testAuth: authHsUnsafe(testAuthSecret) }
  else
    {};

local validatorNodes(clusterProtocol, clusterAddress, port) = {
  alice: {
    jsonApiBackend: { url: 'http://127.0.0.1:6201' },
    jsonApi: { url: 'http://localhost:' + port + '/api/json-api/' },
    participantAdmin: { url: 'http://localhost:6202' },
    validator: { url: 'http://localhost:5503/api/validator' },
    wallet: { uiUrl: 'http://localhost:3000' },
    splitwell: { url: 'http://localhost:5113/api/splitwell' },
    scan: { url: 'http://localhost:5012/api/scan' },
  },
  bob: {
    jsonApiBackend: { url: 'http://127.0.0.1:6301' },
    jsonApi: { url: 'http://localhost:' + port + '/api/json-api/' },
    participantAdmin: { url: 'http://localhost:6302' },
    validator: { url: 'http://localhost:5603/api/validator' },
    wallet: { uiUrl: 'http://localhost:3001' },
    splitwell: { url: 'http://localhost:5113/api/splitwell' },
    scan: { url: 'http://localhost:5012/api/scan' },
  },
  splitwell: {
    validator: { url: 'http://localhost:5703/api/validator' },
    // We need to set this to make the app manager happy but don't actually use it
    // and spinning up a new frontend for this just adds even more resources.
    wallet: { uiUrl: 'http://unused.com' },
    jsonApi: { url: 'http://unused.com/' },
    splitwell: { url: 'http://localhost:5113/api/splitwell' },
    scan: { url: 'http://localhost:5012/api/scan' },
  },
  preflight: {
    jsonApiBackend: { url: 'http://127.0.0.1:7575' },
    jsonApi: { url: 'http://localhost:' + port + '/api/json-api/' },
    participantAdmin: { url: 'http://localhost:8002' },
    validator: { url: 'http://localhost:5003/api/validator' },
    wallet: { uiUrl: 'http://localhost:3000' },
    splitwell: { url: 'http://localhost:5113/api/splitwell' },
    scan: { url: clusterProtocol + '://' + 'scan.sv-2.' + clusterAddress + '/api/scan' },
  },
  scan: {
    scan: { url: 'http://localhost:5012/api/scan' },
  },
  sv1: {
    sv: { url: 'http://localhost:5114/api/sv' },
    validator: { url: 'http://localhost:5103/api/validator' },
    scan: { url: 'http://localhost:5012/api/scan' },
  },
  sv2: {
    sv: { url: 'http://localhost:5214/api/sv' },
    validator: { url: 'http://localhost:5103/api/validator' },
    scan: { url: 'http://localhost:5012/api/scan' },
  },
  sv3: {
    sv: { url: 'http://localhost:5314/api/sv' },
    validator: { url: 'http://localhost:5103/api/validator' },
    scan: { url: 'http://localhost:5012/api/scan' },
  },
  sv4: {
    sv: { url: 'http://localhost:5414/api/sv' },
    validator: { url: 'http://localhost:5103/api/validator' },
    scan: { url: 'http://localhost:5012/api/scan' },
  },
};

local services(node, clusterProtocol, clusterAddress, port) =
  if (std.objectHas(validatorNodes(clusterProtocol, clusterAddress, port), node)) then
    { services: validatorNodes(clusterProtocol, clusterAddress, port)[node] }
  else
    error 'Unknown node name ' + node;

function(
  authAlgorithm='rs-256',
  enableTestAuth,
  validatorNode,
  app,
  clusterProtocol,
  clusterAddress,
  spliceInstanceNames,
  port,
) auth(authAlgorithm) + testAuth(std.parseJson(enableTestAuth)) + services(validatorNode, clusterProtocol, clusterAddress, port) + spliceInstanceNames
