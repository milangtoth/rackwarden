# Changelog

## 2.0.0

The first release under the Rackwarden name. Released for iOS; the Android version can
be built from source but has no download yet.

### New

- **Forward auth:** use services behind a login proxy such as Authentik. Sign in to
  the proxy in the app or send custom headers, set per service. One sign-in can be
  shared by all services, which is on by default and can be turned off in Settings.
- **Two-factor codes:** Nginx Proxy Manager (authenticator), Beszel (emailed code)
  and Dockge (authenticator) ask for a code when they need one.
- **OAuth:** sign in to Beszel with its OAuth provider. Add `rackwarden://oauth` to
  the provider's redirect URIs.
- **Scrutiny:** drive health, temperatures and S.M.A.R.T. attributes, archiving and
  test notifications.
- **Dockge:** stacks and services, including agents, with start, stop, restart and
  update. Built for Dockge 1.5.0.
- **Sign in again from the card** when a session expires, on Home and Servarr.

### Changed

- New name, icon and welcome screen.
- Home and Servarr share the same layout.
- TrueNAS alerts are readable text with their date.
- The Telegram and Reddit contacts are gone.

### Fixed

- TrueNAS dashboards could stay empty because the app signed in too often.
- Expired one-time codes for Proxmox are no longer stored or sent again.
- The GitHub and Tailscale logos are visible in dark mode.

### Updating from 1.6.2

- Saved services stay when you update an app with the same bundle ID. Otherwise,
  move them with a backup.
- Backups from 1.6.2 can be imported. Backups from 2.0.0 also open in 1.6.2, which
  skips Scrutiny, Dockge and forward auth settings.
- Backups keep custom proxy headers but never proxy sessions, so you sign in to the
  proxy again after importing.
