import { createTheme } from '@mui/material/styles';

import { generateHslPalette } from './colors';

// TS module augmentation to add custom theme vars for storing our CN-theme color values
declare module '@mui/material/styles' {
  interface Palette {
    colors: {
      neutral: Record<string, string>;
    };
  }
  // allow configuration using `createTheme`
  interface PaletteOptions {
    colors?: {
      neutral?: Record<string, string>;
    };
  }
}

declare module '@mui/material/Button' {
  interface ButtonPropsVariantOverrides {
    pill: true;
  }
}

let theme = createTheme({
  /**
   * We can add our own custom key/values to the palette, but
   * keep in mind we need to augment the interfaces with whatever we want to add
   *
   *  - https://mui.com/material-ui/customization/theming/#custom-variables
   */
  palette: {
    mode: 'dark',
    colors: {
      neutral: generateHslPalette(215, 29, [20, 25, 30, 40, 50, 60, 70, 80]),
    },
  },
});

theme = createTheme(theme, {
  /**
   * See the following for built-in palette names:
   *
   *  - https://mui.com/system/palette/
   *  - https://mui.com/material-ui/customization/default-theme/
   */
  palette: {
    primary: {
      main: '#003AE1',
    },
    secondary: {
      main: '#71F9B0',
    },
    warning: {
      main: 'indianred',
    },
    background: {
      default: theme.palette.colors.neutral[20],
    },
  },
});

theme = createTheme(theme, {
  /**
   * Style overrides allow us to customize the default look of all components of a given type
   *    - https://mui.com/material-ui/customization/theme-components/#global-style-overrides
   *
   * For components that take "variant" props (e.g., buttons), we can also define custom variants
   *    - https://mui.com/material-ui/customization/theme-components/#creating-new-component-variants
   */
  components: {
    MuiButton: {
      variants: [
        {
          props: { variant: 'pill' },
          style: {
            borderRadius: 9999,
            backgroundColor: theme.palette.primary.main,
            color: 'white',
          },
        },
        {
          props: { variant: 'pill', color: 'warning' },
          style: {
            borderRadius: 9999,
            backgroundColor: theme.palette.warning.main,
            color: 'white',
          },
        },
        {
          props: { variant: 'outlined', color: 'secondary', size: 'small' },
          style: {
            color: 'white',
          },
        },
      ],
    },
    MuiCard: {
      styleOverrides: {
        root: {
          marginBottom: '4px',
          backgroundColor: theme.palette.colors.neutral[20],
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        head: {
          textTransform: 'uppercase',
          fontWeight: 'bold',
        },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: {
          textTransform: 'capitalize',
          fontWeight: 'bold',
          color: 'white',
          '&.Mui-selected': {
            color: 'white',
          },
        },
      },
    },
    MuiTabs: {
      styleOverrides: {
        root: {
          '.MuiTabs-indicator': {
            backgroundColor: theme.palette.secondary.main,
          },
        },
      },
    },
  },
});

export { theme };
