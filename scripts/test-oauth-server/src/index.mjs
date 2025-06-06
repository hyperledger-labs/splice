import { OAuth2Server } from 'oauth2-mock-server';

const port = +(process.argv[2] || 9876);
let server = new OAuth2Server();

// Generate a new RSA key and add it to the keystore
await server.issuer.keys.generate('RS256');


// Log all requests and responses
server.service.on('beforeResponse', (res, req) => {
    try {
        console.log("  req headers: ", JSON.stringify(req.headers));
        console.log("  req body: ", JSON.stringify(req.body));
        console.log("  res body: ", JSON.stringify(res.body));
        console.log("Response sent");
        console.log("");
    } catch {}
});


// In this mock server, we always set the subject equal to the client id
const clientIdFromAuthHeader = (req) => {
    const header = req.header("authorization");
    if (header) {
        return atob(header.replace("Basic ", "")).split(":").slice(0,-1).join(":");
    } else {
        return undefined;
    }

}

const clientIdFromReqBody = (req) => req.body.client_id

server.service.on('beforeTokenSigning', (token /*: MutableToken*/, req /*: express.Request*/) => {
    const subject = clientIdFromAuthHeader(req) || clientIdFromReqBody(req) || "no-subject-found";
    console.log("  subject: ", subject);
    token.payload.sub = subject;
    token.payload.aud = "https://canton.network.global";
})


// Start the server
await server.start(port, '127.0.0.1');
console.log('Mock OAuth2 server started');
console.log('Token endpoint URL:', server.issuer.url + '/token');
console.log('JWKS URL:', server.issuer.url + '/jwks');





