local authScope = 'daml_ledger_api';
local authSecret = 'test';
local testAuthSecret = 'test';


local authHsUnsafe(secret, auth0Config) = {
  algorithm: 'hs-256-unsafe',
  secret: secret,
  token_audience: auth0Config.audience,
  token_scope: authScope,
};

local authRs(auth0Config) = {
  algorithm: 'rs-256',
  authority: auth0Config.authority,
  client_id: auth0Config.clientId,
  token_audience: auth0Config.audience,
  token_scope: authScope,
};


local auth(algorithm, auth0Config) =
  if (algorithm == 'rs-256') then
    { auth: authRs(auth0Config) }
  else if (algorithm == 'hs-256-unsafe') then
    { auth: authHsUnsafe(authSecret, auth0Config) }
  else if (algorithm == 'none') then
    {}
  else
    error 'Unknown auth algorithm' + algorithm;

local testAuth(enabled, auth0Config) =
  if (enabled) then
    { testAuth: authHsUnsafe(testAuthSecret, auth0Config) }
  else
    {};

local validatorNodes(clusterProtocol, clusterAddress, port) = {
  alice: {
    jsonApiBackend: { url: 'http://127.0.0.1:6501' },
    jsonApi: { url: 'http://localhost:' + port + '/api/json-api/' },
    participantAdmin: { url: 'http://localhost:6202' },
    validator: { url: 'http://localhost:5503/api/validator' },
    wallet: { uiUrl: 'http://localhost:3000' },
    splitwell: { url: 'http://localhost:5113/api/splitwell' },
    scan: { url: 'http://localhost:5012' },
  },
  bob: {
    jsonApiBackend: { url: 'http://127.0.0.1:6601' },
    jsonApi: { url: 'http://localhost:' + port + '/api/json-api/' },
    participantAdmin: { url: 'http://localhost:6302' },
    validator: { url: 'http://localhost:5603/api/validator' },
    wallet: { uiUrl: 'http://localhost:3001' },
    splitwell: { url: 'http://localhost:5113/api/splitwell' },
    scan: { url: 'http://localhost:5012' },
  },
  splitwell: {
    validator: { url: 'http://localhost:5703/api/validator' },
    wallet: { uiUrl: 'http://unused.com' },
    jsonApi: { url: 'http://unused.com/' },
    splitwell: { url: 'http://localhost:5113/api/splitwell' },
    scan: { url: 'http://localhost:5012' },
  },
  preflight: {
    jsonApiBackend: { url: 'http://127.0.0.1:7575' },
    jsonApi: { url: 'http://localhost:' + port + '/api/json-api/' },
    participantAdmin: { url: 'http://localhost:8002' },
    validator: { url: 'http://localhost:5003/api/validator' },
    wallet: { uiUrl: 'http://localhost:3000' },
    splitwell: { url: 'http://localhost:5113/api/splitwell' },
    scan: { url: clusterProtocol + '://' + 'scan.sv-2.' + clusterAddress + '' },
  },
  scan: {
    scan: { url: 'http://localhost:5012' },
  },
  sv1: {
    sv: { url: 'http://localhost:5114/api/sv' },
    validator: { url: 'http://localhost:5103/api/validator' },
    scan: { url: 'http://localhost:5012' },
  },
  sv2: {
    sv: { url: 'http://localhost:5214/api/sv' },
    validator: { url: 'http://localhost:5103/api/validator' },
    scan: { url: 'http://localhost:5012' },
  },
  sv3: {
    sv: { url: 'http://localhost:5314/api/sv' },
    validator: { url: 'http://localhost:5103/api/validator' },
    scan: { url: 'http://localhost:5012' },
  },
  sv4: {
    sv: { url: 'http://localhost:5414/api/sv' },
    validator: { url: 'http://localhost:5103/api/validator' },
    scan: { url: 'http://localhost:5012' },
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
  auth0Config,
  validatorNode,
  app,
  clusterProtocol,
  clusterAddress,
  spliceInstanceNames,
  port,
) auth(authAlgorithm, auth0Config) + testAuth(std.parseJson(enableTestAuth), auth0Config) + services(validatorNode, clusterProtocol, clusterAddress, port) + spliceInstanceNames
