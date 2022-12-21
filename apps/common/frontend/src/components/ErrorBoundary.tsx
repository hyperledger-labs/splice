import { ReactNode } from 'react';
import React from 'react';

import { Button } from '@mui/material';

const ErrorBanner: React.FC<{
  error?: string;
  errorTime?: Date;
  clearError: () => void;
}> = ({ error, errorTime, clearError }) => (
  <div
    style={{
      padding: '4px',
      background: '#ffe0e0',
      display: 'flex',
      justifyContent: 'space-between',
    }}
  >
    <div>
      Something went wrong. Latest error ({errorTime?.toLocaleString()}): <br />{' '}
      <span id="error">{error}</span>
    </div>
    <Button id="clear-error-button" variant="outlined" onClick={clearError}>
      Clear
    </Button>
  </div>
);

interface IProps {
  children?: ReactNode;
}

interface IState {
  hasError: boolean;
  error?: string;
  errorTime?: Date;
}

const DEFAULT_STATE = {
  hasError: false,
} as IState;

function getErrorState(error: string): IState {
  return {
    hasError: true,
    error: error,
    errorTime: new Date(),
  };
}

class ErrorBoundary extends React.Component<IProps, IState> {
  public state = DEFAULT_STATE;

  private setError = (error: string) => {
    this.setState(getErrorState(error));
  };

  private clearError = async () => {
    this.setState(DEFAULT_STATE);
  };

  private promiseRejectionHandler = (event: PromiseRejectionEvent) => {
    console.error('ErrorBoundary caught an unhandled promise rejection', event.reason.toString());
    this.setError(event.reason.toString());
  };

  private static getDerivedStateFromError(error: Error) {
    return getErrorState(error.toString());
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    // Placeholder for potentially logging the error further
    console.error(
      'ErrorBoundary caught an error',
      error.name,
      error.message,
      error.stack,
      errorInfo.componentStack
    );
  }

  componentDidMount(): void {
    window.addEventListener('unhandledrejection', this.promiseRejectionHandler);
  }
  componentWillUnmount(): void {
    window.removeEventListener('unhandledrejection', this.promiseRejectionHandler);
  }

  render(): ReactNode {
    let errorBanner = null;

    if (this.state.hasError) {
      errorBanner = (
        <ErrorBanner
          error={this.state.error}
          errorTime={this.state.errorTime}
          clearError={() => this.clearError()}
        />
      );
    }

    return (
      <div>
        {errorBanner}
        {this.props.children}
      </div>
    );
  }
}

export default ErrorBoundary;
