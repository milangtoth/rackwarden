<div align="center">

<img src="media-docs/readme/app-icon.png" width="128" alt="Rackwarden app icon: a mint castle tower built from server racks">

# Rackwarden

**Your homelab, guarded and organized.**<br>
A native app to watch and manage your self-hosted services from one place,<br>
even when they sit behind a login proxy.

[![iOS 26+](https://img.shields.io/badge/iOS-26%2B-000000?logo=apple&logoColor=white)](#ios)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#android)
[![SwiftUI](https://img.shields.io/badge/SwiftUI-Swift%206-F05138?logo=swift&logoColor=white)](HomelabSwift)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Kotlin-7F52FF?logo=kotlin&logoColor=white)](HomelabAndroid)
[![36 services](https://img.shields.io/badge/services-36-12D6B3)](#services)
[![Apache 2.0 License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

<br>

<table>
<tr>
<td align="center"><img src="media-docs/readme/welcome-dark.png" width="200" alt="Welcome screen with the Rackwarden icon"><br><sub><b>Welcome</b></sub></td>
<td align="center"><img src="media-docs/readme/home-dark.png" width="200" alt="Home tab with a grid of services"><br><sub><b>Home</b></sub></td>
<td align="center"><img src="media-docs/readme/servarr-dark.png" width="200" alt="Servarr tab with media services"><br><sub><b>Servarr</b></sub></td>
<td align="center"><img src="media-docs/readme/settings-dark.png" width="200" alt="Settings with app icons, language and security"><br><sub><b>Settings</b></sub></td>
</tr>
</table>

</div>

---

> [!NOTE]
> Rackwarden hasn't published a release yet, so for now you [build it from source](#install).
> Version 2.0.0 is coming to iOS first. The Android app and the project folders still use the Homelab name.

## Why

A homelab grows one web page at a time, and most of those pages weren't made for
a phone. Put them behind a proxy such as Authentik, add two-factor codes, and
simply checking whether everything is fine takes a dozen logins.

Rackwarden brings them together as native dashboards: what's online, what needs
attention, and the few actions you actually want on the go.

## Features

- **36 services in one app:** containers, storage, networking, media and
  monitoring. Add several instances of the same service and switch between them.
- **Works behind forward auth:** sign in to your proxy in the app or send
  custom headers, set per service. One sign-in can cover every service. ¹
- **Two-factor codes and OAuth:** one-time codes for services such as Nginx
  Proxy Manager, Beszel and Dockge, and OAuth sign-in for Beszel. ¹
- **Fallback addresses:** give a service a second URL, for example local and remote.
- **Locked down:** PIN and biometric unlock, with service credentials kept in
  the iOS Keychain.
- **Encrypted backups:** move your services to another device with a
  password-protected file.
- **Made to feel at home:** light and dark mode, alternate icons, and English,
  German, French, Italian and Spanish.
- **Beyond services:** bookmarks, plus a shortcut to Tailscale when you need it.

<sub>¹ iOS only for now.</sub>

## Services

### Infrastructure and monitoring

| | Service | What you get |
|:-:|---|---|
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/portainer.png" width="22" alt=""> | **Portainer** | Containers, quick actions and resource usage |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/dockge.png" width="22" alt=""> | **Dockge ¹** | Compose stacks and services, agents, start, stop, restart and update |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/dockhand.png" width="22" alt=""> | **Dockhand** | Container management |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/dockmon.png" width="22" alt=""> | **DockMon** | Docker hosts and containers, logs, restart and update |
| <img src="HomelabSwift/Homelab/Assets.xcassets/service-komodo.imageset/komodo.png" width="22" alt=""> | **Komodo** | Resources, deployments, stacks and servers |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/proxmox.png" width="22" alt=""> | **Proxmox VE** | Nodes, guests, storage, networking, backups and cluster operations |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/truenas-scale.png" width="22" alt=""> | **TrueNAS SCALE / CORE** | Pools, disks, shares, services and alerts |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/scrutiny.png" width="22" alt=""> | **Scrutiny ¹** | Drive health, temperatures, S.M.A.R.T. attributes and archiving |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/beszel.png" width="22" alt=""> | **Beszel** | Server monitoring across nodes |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/uptime-kuma.png" width="22" alt=""> | **Uptime Kuma** | Monitor status and incidents |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/healthchecks.png" width="22" alt=""> | **Healthchecks** | Checks and health status |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/linux-update-dashboard.png" width="22" alt=""> | **Linux Update** | Pending package updates across hosts |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/patchmon.png" width="22" alt=""> | **PatchMon** | Software updates across your stack |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/gitea.png" width="22" alt=""> | **Gitea / Forgejo** | Repositories, activity and source browsing |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/wakapi.png" width="22" alt=""> | **Wakapi** | Coding activity and time tracking |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/crafty-controller.png" width="22" alt=""> | **Crafty Controller** | Game server management |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/pterodactyl.png" width="22" alt=""> | **Pterodactyl** | Game servers with power controls and live resources |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/calagopus.png" width="22" alt=""> | **Calagopus** | Game servers with power controls, uptime and resources |

### Networking and DNS

| | Service | What you get |
|:-:|---|---|
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/nginx-proxy-manager.png" width="22" alt=""> | **Nginx Proxy Manager / NPMplus** | Proxy hosts, streams, redirects, certificates and access lists |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/pi-hole.png" width="22" alt=""> | **Pi-hole** | Queries, blocked domains, toggles and timers |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/adguard-home.png" width="22" alt=""> | **AdGuard Home** | Filters, rewrites, blocked services and query activity |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/technitium.png" width="22" alt=""> | **Technitium DNS** | DNS metrics and health |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/ubiquiti-unifi.png" width="22" alt=""> | **Ubiquiti Network** | Gateways, switches, access points, clients and sites |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/pangolin.png" width="22" alt=""> | **Pangolin / Newt** | Tunnels and peers |
| <img src="HomelabSwift/Homelab/Assets.xcassets/service-maltrail.imageset/icon.png" width="22" alt=""> | **Maltrail** | Threat detections and daily findings |

### Media and Servarr

| | Service | What you get |
|:-:|---|---|
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/plex.png" width="22" alt=""> | **Plex** | Libraries, sessions and recently added media |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/jellystat.png" width="22" alt=""> | **Jellystat** | Jellyfin activity, streams and usage |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/sonarr.png" width="22" alt=""> | **Sonarr** | TV library, queue, health and upcoming episodes |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/radarr.png" width="22" alt=""> | **Radarr** | Movie library, queue, health and upcoming releases |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/lidarr.png" width="22" alt=""> | **Lidarr** | Music library, queue, health and upcoming releases |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/prowlarr.png" width="22" alt=""> | **Prowlarr** | Indexers, connected apps and issues |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/bazarr.png" width="22" alt=""> | **Bazarr** | Subtitles, providers and issues |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/jellyseerr.png" width="22" alt=""> | **Jellyseerr** | Requests and availability |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/qbittorrent.png" width="22" alt=""> | **qBittorrent** | Transfers, speeds and connection status |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/gluetun.png" width="22" alt=""> | **Gluetun** | VPN status, public IP and forwarded port |
| <img src="https://cdn.jsdelivr.net/gh/selfhst/icons/png/flaresolverr.png" width="22" alt=""> | **FlareSolverr** | Sessions and status |

<sub>¹ iOS only for now, so Android has 34 services.</sub>

## Install

There are no downloads yet. Once released, builds will appear on the
[Releases page](https://github.com/milangtoth/Rackwarden/releases).

### iOS

You need a Mac with Xcode 26 or later and a device or simulator with iOS 26.

**1. Get the code**

```bash
git clone https://github.com/milangtoth/Rackwarden.git
cd Rackwarden
cp HomelabSwift/Config/Signing.xcconfig.example HomelabSwift/Config/Signing.xcconfig
```

**2. Set your signing details**

Put your own bundle ID and development team in `HomelabSwift/Config/Signing.xcconfig`.
Git ignores that file, so set them there rather than under **Signing & Capabilities**.

**3. Run it**

Open `HomelabSwift/Homelab.xcodeproj`, choose your device and press **⌘R**. With a
free Apple account, the app has to be reinstalled every 7 days.

### Android

Open `HomelabAndroid` in Android Studio, let Gradle sync, and run it on a device or
emulator with Android 8.0 or later.

## Usage

| | |
|---|---|
| **Add a service** | Tap its card, enter the address and sign in. |
| **Use a proxy login** | Turn on **Forward auth?** before signing in, then connect. |
| **Skip the service's own login** | Choose **No separate login**, where the service supports it. |
| **Sign in with OAuth** | Tap **Sign in with OAuth** (Beszel). |
| **Share one proxy sign-in** | **Settings → Forward auth**, on by default. |
| **Rearrange or hide services** | Tap **⇅** on Home or Servarr. |
| **Move to another device** | Export and import a backup in **Settings**. |

### Behind a login proxy

```mermaid
flowchart LR
    A["Rackwarden"] -- "proxy session or headers" --> P["Forward auth proxy<br/><sub>such as Authentik</sub>"]
    P -- "allowed" --> S["Your service"]
    A -. "then, if needed" .-> L["Service login<br/><sub>password, API key, 2FA or OAuth</sub>"]
    L -.-> S
```

1. Turn on **Forward auth?** and sign in to the proxy in the app, or enter custom
   headers such as those for Cloudflare Access.
2. Rackwarden checks that the proxy lets it through, and only then asks for the
   service's own login.
3. When the proxy session expires, the card shows **Sign in**. Your service login
   is kept.

<details>
<summary><b>Good to know</b></summary>

- Password autofill is limited and passkeys may not work in the in-app sign-in page.
- How long you stay signed in depends on your proxy's session settings.
- The Proxmox console doesn't go through forward auth.
- For Beszel OAuth, add `rackwarden://oauth` to your provider's redirect URIs.

</details>

## Privacy and security

- **No account and no Rackwarden server.** The app talks to your services directly.
- **Few outside requests.** Service icons come from public icon hosts, some artwork
  and bookmark icons from third parties, and the update check from GitHub.
- **Proxy credentials stay put.** They're only sent to the address they belong to,
  never to another site after a redirect. Proxy sessions aren't included in backups.
- **Careful actions.** Archiving drives and stopping or updating Dockge stacks asks
  for confirmation first.

See the [privacy policy](docs/privacy.html) for details.

## Development

Build and test commands, branch conventions and release steps are in [AGENTS.md](AGENTS.md).
What changed in each version is in the [changelog](CHANGELOG.md).

<details>
<summary><b>Project layout</b></summary>

```
HomelabSwift/      iOS app (Swift 6, SwiftUI) and its tests
HomelabAndroid/    Android app (Kotlin, Jetpack Compose)
docs/              privacy and support pages
media-docs/        icons and screenshots
apps.json          AltStore Classic / SideStore source, filled by releases
app-version.json   version info for the in-app update check
```

</details>

## Contributing

Bug reports, compatibility issues and feature requests are welcome in
[GitHub Issues](https://github.com/milangtoth/Rackwarden/issues), and so are pull
requests. Please don't report Rackwarden issues to the original project.

## License

[Apache License 2.0](LICENSE), the same license as the original project. The
software is provided as is, without warranties.

## Acknowledgements

- Built on [Homelab Dashboard](https://github.com/JohnnWi/homelab-project) by
  [JohnnWi](https://github.com/JohnnWi), now archived, with contributions from
  [russssl](https://github.com/russssl), [FlintyLemming](https://github.com/FlintyLemming)
  and [vladtaylor](https://github.com/vladtaylor). The full commit history is preserved here.
- Service icons from [selfh.st/icons](https://selfh.st/icons/).

<sub>Rackwarden is an independent continuation and is not affiliated with or endorsed by the original author or the services it connects to. Product names and logos belong to their owners.</sub>
