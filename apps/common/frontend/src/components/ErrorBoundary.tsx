import { ReactNode } from 'react';
import React from 'react';

import { Button } from '@mui/material';

interface IProps {
  children?: ReactNode;
}

interface IState {
  hasError?: boolean;
  error?: string;
}

class ErrorBoundary extends React.Component<IProps, IState> {
  constructor(props: IProps) {
    super(props);
    this.state = { hasError: false };
  }
  private promiseRejectionHandler = (event: PromiseRejectionEvent) => {
    console.error('ErrorBoundary caught an unhandled promise rejection', event.reason.toString());
    this.setState({
      hasError: true,
      error: event.reason.toString(),
    });
  };
  private static getDerivedStateFromError(error: Error) {
    // Update state so the next render will show the fallback UI.
    return { hasError: true, error: error.toString() };
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
    // Add an event listener to the window to catch unhandled promise rejections & stash the error in the state
    window.addEventListener('unhandledrejection', this.promiseRejectionHandler);
  }
  componentWillUnmount(): void {
    window.removeEventListener('unhandledrejection', this.promiseRejectionHandler);
  }
  private clearError = async () => {
    this.setState({
      hasError: false,
      error: '',
    });
  };

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <div>
          <div>
            <h1>
              Something went wrong. Latest error: <br /> <span id="error">{this.state.error}</span>
            </h1>
            <Button id="clear-error-button" variant="outlined" onClick={() => this.clearError()}>
              Clear
            </Button>
          </div>
          {this.props.children}
        </div>
      );
    }
    return this.props.children;
  }
}

export default ErrorBoundary;
