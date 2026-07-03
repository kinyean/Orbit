// Top-bar identity + logout (Phase 10). Rendered only in oidc mode (see App),
// so useAuth() is always inside the provider. Shows the signed-in email and a
// logout button.

import { useAuth } from 'react-oidc-context';

export default function UserChip() {
  const auth = useAuth();
  if (!auth.isAuthenticated) return null;

  const profile = auth.user?.profile;
  const label = profile?.email ?? profile?.preferred_username ?? profile?.name ?? 'signed in';

  return (
    <div className="user-chip" title={String(label)}>
      <span className="user-chip-email">{String(label)}</span>
      <button
        className="user-chip-logout"
        onClick={() => void auth.signoutRedirect()}
        title="Sign out"
      >
        Sign out
      </button>
    </div>
  );
}
