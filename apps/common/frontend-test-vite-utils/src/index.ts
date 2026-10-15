const cacheDir = process.env.PORT ? `node_modules/.vite-${process.env.PORT}` : 'node_modules/.vite';

export default {
  cacheDir,
  test: {
    disableConsoleIntercept: true,
    environment: 'happy-dom',
    exclude: ['../lib/**'],
    silent: false,
    testTimeout: 15000,
    typecheck: {
      include: ['**/*.{test,spec}-d.?(c|m)[jt]s?(x)', '**/*.{test,spec}.?(c|m)[jt]s?(x)'],
    },
    // https://stackoverflow.com/questions/79592526/testing-error-after-upgrading-mui-x-data-grid-to-v8-1-0-unknown-file-extensio
    server: {
      deps: {
        inline: ['@mui/x-data-grid', '@mui/x-date-pickers'],
      },
    },
  },
};
