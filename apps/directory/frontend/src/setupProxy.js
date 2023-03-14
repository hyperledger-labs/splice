const { createProxyMiddleware } = require('http-proxy-middleware');

const httpJsonDevUrl = 'http://127.0.0.1:7575';
/**
 * @return {Boolean}
 */
const filter = function (pathname, req) {
  // Proxy requests to the http json api when in development to workaround cors restrictions.
  const proxied = pathname.match('^/api/json-api/') && process.env.NODE_ENV === 'development';

  if (proxied) {
    console.log(
      `Request with path ${pathname} proxied from host ${req.headers.host} to host ${httpJsonDevUrl}`
    );
  }

  return proxied;
};

module.exports = function (app) {
  app.use(
    createProxyMiddleware(filter, {
      target: httpJsonDevUrl,
      pathRewrite: { '^/api/json-api/': '' },
    })
  );
};
