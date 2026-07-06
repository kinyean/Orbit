import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  /** The subtree to protect. */
  children: ReactNode;
  /** Short label shown in the fallback (e.g. "globe view"). */
  label?: string;
}

interface State {
  error: Error | null;
}

/**
 * Catches render / lifecycle errors in a subtree so one component's crash degrades to a
 * small inline notice (with a retry) instead of blanking the whole app. The motivating
 * case: a Cesium hiccup during a stream drop / backend restart can throw inside Globe —
 * and an effect-cleanup that throws during the resulting error-unmount cascades. Without a
 * boundary, React unmounts the entire tree and the user sees a blank screen.
 *
 * <p>Note: error boundaries do NOT catch async/promise or event-handler errors — those are
 * guarded at their source (e.g. the destroyed-viewer check in {@code Globe.tsx}). This is
 * defense-in-depth for the synchronous render/lifecycle path.
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // eslint-disable-next-line no-console
    console.error(`ErrorBoundary (${this.props.label ?? 'subtree'}) caught:`, error, info.componentStack);
  }

  private reset = () => this.setState({ error: null });

  render() {
    const { error } = this.state;
    if (error) {
      return (
        <div
          role="alert"
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '0.75rem',
            height: '100%',
            padding: '2rem',
            textAlign: 'center',
            color: '#cbd5e1',
            background: '#0b1020',
          }}
        >
          <p style={{ margin: 0 }}>The {this.props.label ?? 'view'} hit an error and was paused.</p>
          <p style={{ margin: 0, fontSize: '0.8rem', opacity: 0.7, maxWidth: '40ch' }}>{error.message}</p>
          <button title="Reload this view" type="button" onClick={this.reset}>
            Reload view
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
