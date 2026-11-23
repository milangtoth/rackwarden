import SwiftUI

/// Title bar shared by the Home and Servarr tabs.
struct LauncherHeader: View {
    let title: String
    let icon: String
    let count: Int
    let onReorder: () -> Void

    @Environment(Localizer.self) private var localizer

    var body: some View {
        HStack {
            Text(title)
                .font(.largeTitle)
                .fontWeight(.heavy)
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)

            Spacer()

            HStack(spacing: 8) {
                HStack(spacing: 5) {
                    Image(systemName: icon)
                        .font(.caption2)
                        .accessibilityHidden(true)
                    Text("\(count)")
                        .font(.subheadline.bold())
                }
                .foregroundStyle(AppTheme.accent)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .glassCard(cornerRadius: 20, tint: AppTheme.accent.opacity(0.15))

                Button {
                    HapticManager.light()
                    onReorder()
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                        .font(.subheadline.bold())
                        .foregroundStyle(AppTheme.accent)
                        .frame(width: 36, height: 36)
                        .background(AppTheme.accent.opacity(0.12), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(localizer.t.homeReorderServices)
            }
        }
        .padding(.top, 8)
        .padding(.bottom, 16)
    }
}

struct LauncherFooter: View {
    let count: Int
    @Environment(Localizer.self) private var localizer

    var body: some View {
        Text("\(localizer.t.launcherServices) • \(count) \(localizer.t.launcherConnected.sentenceCased())")
            .font(.caption)
            .foregroundStyle(AppTheme.textMuted)
            .frame(maxWidth: .infinity)
            .padding(.top, 28)
            .padding(.bottom, 40)
    }
}

/// VPN status shared by both tabs, so it looks the same and appears at the same time on each.
struct TailscaleCard: View {
    @Environment(ServicesStore.self) private var servicesStore
    @Environment(Localizer.self) private var localizer

    private var isConnected: Bool { servicesStore.isTailscaleConnected }

    private var isVisible: Bool {
        if isConnected { return servicesStore.instances(for: .pangolin).isEmpty }
        return servicesStore.allInstances.contains { servicesStore.reachability(for: $0.id) == false }
    }

    var body: some View {
        if isVisible {
            Button(action: open) { content }
                .buttonStyle(.plain)
                .padding(.bottom, 16)
        }
    }

    private var content: some View {
        HStack(spacing: 16) {
            TailscaleLogo()
                .frame(width: 24, height: 24)
                .frame(width: 44, height: 44)
                .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(isConnected ? localizer.t.tailscaleConnected : localizer.t.tailscaleOpen)
                    .font(.subheadline.bold())
                    .foregroundStyle(.primary)
                Text(isConnected ? localizer.t.tailscaleSecure : localizer.t.tailscaleOpenDesc)
                    .font(.caption)
                    .foregroundStyle(AppTheme.textMuted)
                    .lineLimit(2)
            }

            Spacer(minLength: 0)

            if isConnected {
                Image(systemName: "checkmark.seal.fill")
                    .foregroundStyle(AppTheme.running)
                    .font(.title3)
                    .accessibilityHidden(true)
            } else {
                HStack(spacing: 4) {
                    Text(localizer.t.tailscaleBadge)
                        .font(.caption2.bold())
                    Image(systemName: "chevron.right")
                        .font(.caption2)
                        .accessibilityHidden(true)
                }
                .foregroundStyle(AppTheme.textMuted)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Color.gray.opacity(0.1), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .glassCard(tint: isConnected ? AppTheme.running.opacity(0.05) : nil)
        .contentShape(Rectangle())
    }

    private func open() {
        HapticManager.medium()
        guard let url = URL(string: "tailscale://app") else { return }
        UIApplication.shared.open(url, options: [:]) { success in
            if !success, let store = URL(string: "https://apps.apple.com/app/tailscale/id1475387142") {
                UIApplication.shared.open(store)
            }
        }
    }
}

/// Drawn instead of downloaded, so it follows light and dark mode.
struct TailscaleLogo: View {
    private static let filled: Set<Int> = [3, 4, 5, 7]

    var body: some View {
        Canvas { context, size in
            let diameter = min(size.width, size.height) * 0.26
            let step = (min(size.width, size.height) - diameter) / 2
            for index in 0..<9 {
                let rect = CGRect(
                    x: CGFloat(index % 3) * step, y: CGFloat(index / 3) * step, width: diameter, height: diameter
                )
                context.fill(
                    Path(ellipseIn: rect),
                    with: .color(.primary.opacity(Self.filled.contains(index) ? 1 : 0.3))
                )
            }
        }
        .accessibilityHidden(true)
    }
}
