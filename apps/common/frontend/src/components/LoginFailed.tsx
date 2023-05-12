import { ReactNode } from 'react';
import React from 'react';

interface IProps {
  children?: ReactNode;
}

class LoginFailed extends React.Component<IProps> {
  render(): ReactNode {
    return (
      <div>
        <div
          style={{
            padding: '8px',
            background: '#FFCC99',
            display: 'flex',
            justifyContent: 'space-between',
          }}
        >
          <div>
            <span id="loginFailed">User failed to login!</span>
          </div>
        </div>
        {this.props.children}
      </div>
    );
  }
}

export default LoginFailed;
