import { createTheme, TypographyStyle } from '@mui/material';

import { generateHslPalette } from './colors';

// TS module augmentation to add custom theme vars for storing our CN-theme color values
declare module '@mui/material/styles' {
  interface Theme {
    fonts: {
      sansSerif: TypographyStyle;
      monospace: TypographyStyle;
    };
  }

  interface ThemeOptions {
    fonts?: {
      sansSerif: TypographyStyle;
      monospace: TypographyStyle;
    };
  }

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
  fonts: {
    sansSerif: {
      fontFamily: '"Lato", sans-serif',
      fontWeight: 400,
    },
    monospace: {
      fontFamily: '"Source Code Pro", monospace',
      fontWeight: 500,
    },
  },
});

theme = createTheme(theme, {
  typography: {
    // I couldn't figure out a less verbose way to reliably set the font family... specifying it at
    // `theme.typography.fontFamily`, as indicated by the documentation, didn't actually do anything
    h1: theme.fonts.sansSerif,
    h2: theme.fonts.sansSerif,
    h3: theme.fonts.sansSerif,
    h4: theme.fonts.sansSerif,
    h5: theme.fonts.sansSerif,
    h6: theme.fonts.sansSerif,
    subtitle1: theme.fonts.sansSerif,
    subtitle2: theme.fonts.sansSerif,
    body1: theme.fonts.sansSerif,
    body2: theme.fonts.sansSerif,
    button: theme.fonts.sansSerif,
    caption: theme.fonts.sansSerif,
    overline: theme.fonts.sansSerif,
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
