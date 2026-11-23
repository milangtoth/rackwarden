import SwiftUI

struct MediaDashboardView: View {
    @Environment(ServicesStore.self) private var servicesStore
    @Environment(SettingsStore.self) private var settingsStore
    @Environment(Localizer.self) private var localizer

    @AppStorage("homelab_media_arr_tutorial_dismissed") private var hasDismissedTutorial = false
    @State private var quickSetupType: ServiceType?
    @State private var showLogin: ServiceType? = nil
    @State private var signInContext: ServiceEditorContext?
    @State private var proxySignIn: ForwardAuthTarget?
    @State private var showingMediaServiceOrder = false
    @State private var cardPreviewsByInstanceId: [UUID: MediaCardPreviewData] = [:]
    @State private var previewLoadingIds: Set<UUID> = []
    @State private var previewErrorIds: Set<UUID> = []
    @State private var previewLastLoadedAt: [UUID: Date] = [:]

    private let columns = [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)]
    private let previewMinRefreshInterval: TimeInterval = 90

    private var hiddenServiceKeys: Set<String> {
        settingsStore.hiddenServices
    }

    private var mediaServices: [ServiceType] {
        settingsStore.serviceOrder.filter { ServiceType.mediaServices.contains($0) && !hiddenServiceKeys.contains($0.rawValue) }
    }

    private var unconfiguredMediaServices: [ServiceType] {
        mediaServices.filter { !servicesStore.hasInstances(for: $0) }
    }

    private var connectedMediaCount: Int {
        mediaServices.reduce(0) { partial, type in
            partial + servicesStore.instances(for: type).count
        }
    }

    private var gridEntries: [MediaGridEntry] {
        mediaServices.flatMap { type -> [MediaGridEntry] in
            let instances = servicesStore.instances(for: type)
            if instances.isEmpty {
                return [MediaGridEntry.disconnected(type: type)]
            }
            return instances.map { instance in
                MediaGridEntry.connected(type: type, instance: instance)
            }
        }
    }

    private var arr: ArrStrings {
        localizer.arr
    }

    @MainActor
    private func requestCardPreview(for instance: ServiceInstance, force: Bool = false) async {
        guard servicesStore.reachability(for: instance.id) != false else { return }
        if previewLoadingIds.contains(instance.id) { return }

        if !force,
           let lastLoaded = previewLastLoadedAt[instance.id],
           Date().timeIntervalSince(lastLoaded) < previewMinRefreshInterval,
           cardPreviewsByInstanceId[instance.id] != nil {
            return
        }

        previewLoadingIds.insert(instance.id)
        previewErrorIds.remove(instance.id)
        defer { previewLoadingIds.remove(instance.id) }

        do {
            let preview = try await loadCardPreview(for: instance)
            cardPreviewsByInstanceId[instance.id] = preview
            previewLastLoadedAt[instance.id] = Date()
            previewErrorIds.remove(instance.id)
        } catch {
            if cardPreviewsByInstanceId[instance.id] == nil {
                previewErrorIds.insert(instance.id)
            }
        }
    }

    private func loadCardPreview(for instance: ServiceInstance) async throws -> MediaCardPreviewData {
        switch instance.type {
        case .qbittorrent:
            guard let client = await servicesStore.qbittorrentClient(instanceId: instance.id) else {
                throw APIError.notConfigured
            }
            let transfer = try await client.getTransferInfo()
            return MediaCardPreviewData(
                headline: transfer.connection_status.capitalized,
                metrics: [
                    MediaCardPreviewMetric(label: arr.download, value: "\(Formatters.formatBytes(Double(transfer.dl_info_speed)))/s"),
                    MediaCardPreviewMetric(label: arr.upload, value: "\(Formatters.formatBytes(Double(transfer.up_info_speed)))/s"),
                    MediaCardPreviewMetric(label: arr.dhtLabel, value: "\(transfer.dht_nodes ?? 0)")
                ]
            )
        case .radarr:
            guard let client = await servicesStore.radarrClient(instanceId: instance.id) else {
                throw APIError.notConfigured
            }
            async let statusTask = client.getSystemStatus()
            async let queueTask = client.getQueue()
            async let healthTask = client.getHealthMessages()
            async let upcomingTask = client.getUpcomingTitles(limit: 8)
            let status = try await statusTask
            let queue = try await queueTask
            let health = await healthTask
            let upcoming = await upcomingTask
            return MediaCardPreviewData(
                headline: "v\(status.version) • \(status.displayBranch)",
                metrics: [
                    MediaCardPreviewMetric(label: arr.download, value: "\(queue.totalRecords)"),
                    MediaCardPreviewMetric(label: arr.health, value: "\(health.count)"),
                    MediaCardPreviewMetric(label: arr.upcoming, value: "\(upcoming.count)")
                ]
            )
        case .sonarr:
            guard let client = await servicesStore.sonarrClient(instanceId: instance.id) else {
                throw APIError.notConfigured
            }
            async let statusTask = client.getSystemStatus()
            async let queueTask = client.getQueue()
            async let healthTask = client.getHealthMessages()
            async let upcomingTask = client.getUpcomingTitles(limit: 8)
            let status = try await statusTask
            let queue = try await queueTask
            let health = await healthTask
            let upcoming = await upcomingTask
            return MediaCardPreviewData(
                headline: "v\(status.version) • \(status.displayBranch)",
                metrics: [
                    MediaCardPreviewMetric(label: arr.download, value: "\(queue.totalRecords)"),
                    MediaCardPreviewMetric(label: arr.health, value: "\(health.count)"),
                    MediaCardPreviewMetric(label: arr.upcoming, value: "\(upcoming.count)")
                ]
            )
        case .lidarr:
            guard let client = await servicesStore.lidarrClient(instanceId: instance.id) else {
                throw APIError.notConfigured
            }
            async let statusTask = client.getSystemStatus()
            async let queueTask = client.getQueue()
            async let healthTask = client.getHealthMessages()
            async let upcomingTask = client.getUpcomingTitles(limit: 8)
            let status = try await statusTask
            let queue = try await queueTask
            let health = await healthTask
            let upcoming = await upcomingTask
            return MediaCardPreviewData(
                headline: "v\(status.version) • \(status.displayBranch)",
                metrics: [
                    MediaCardPreviewMetric(label: arr.download, value: "\(queue.totalRecords)"),
                    MediaCardPreviewMetric(label: arr.health, value: "\(health.count)"),
                    MediaCardPreviewMetric(label: arr.upcoming, value: "\(upcoming.count)")
                ]
            )
        case .jellyseerr, .prowlarr, .bazarr, .gluetun, .flaresolverr:
            guard let client = await servicesStore.genericMediaClient(instanceId: instance.id) else {
                throw APIError.notConfigured
            }
            guard let snapshot = await client.serviceSnapshot() else {
                throw APIError.custom("No data")
            }
            return genericPreview(from: snapshot)
        default:
            throw APIError.notConfigured
        }
    }

    private func genericPreview(from snapshot: GenericServiceSnapshot) -> MediaCardPreviewData {
        switch snapshot {
        case .jellyseerr(let data):
            return MediaCardPreviewData(
                headline: data.version.map { "v\($0)" },
                metrics: [
                    MediaCardPreviewMetric(label: arr.requests, value: "\(data.totalRequests)"),
                    MediaCardPreviewMetric(label: arr.pending, value: "\(data.pendingRequests)"),
                    MediaCardPreviewMetric(label: arr.available, value: "\(data.availableRequests)")
                ]
            )
        case .prowlarr(let data):
            return MediaCardPreviewData(
                headline: data.version.map { "v\($0)" },
                metrics: [
                    MediaCardPreviewMetric(label: arr.indexers, value: "\(data.indexers.count)"),
                    MediaCardPreviewMetric(label: arr.apps, value: "\(data.applications.count)"),
                    MediaCardPreviewMetric(label: arr.issues, value: "\(data.unhealthyCount)")
                ]
            )
        case .bazarr(let data):
            let wanted = data.badges.first(where: { $0.label.lowercased() == "wanted" })?.value
            let missing = data.badges.first(where: { $0.label.lowercased() == "missing" })?.value
            let providers = data.badges.first(where: { $0.label.lowercased() == "providers" })?.value
            return MediaCardPreviewData(
                headline: data.version.map { "v\($0)" },
                metrics: [
                    MediaCardPreviewMetric(label: arr.subtitles, value: wanted ?? "0"),
                    MediaCardPreviewMetric(label: arr.issues, value: "\(data.issues.count)"),
                    MediaCardPreviewMetric(label: arr.provider, value: providers ?? missing ?? "0")
                ]
            )
        case .gluetun(let data):
            return MediaCardPreviewData(
                headline: data.vpnProvider ?? data.serverName ?? data.connectionStatus,
                metrics: [
                    MediaCardPreviewMetric(label: arr.statusLabel, value: data.connectionStatus ?? localizer.t.noData),
                    MediaCardPreviewMetric(label: arr.publicIPLabel, value: data.publicIP ?? localizer.t.noData),
                    MediaCardPreviewMetric(label: arr.forwardedPort, value: data.forwardedPort ?? localizer.t.noData)
                ]
            )
        case .flaresolverr(let data):
            return MediaCardPreviewData(
                headline: data.version.map { "v\($0)" } ?? data.message,
                metrics: [
                    MediaCardPreviewMetric(label: arr.sessions, value: "\(data.sessions.count)"),
                    MediaCardPreviewMetric(label: arr.statusLabel, value: data.status ?? localizer.t.noData)
                ]
            )
        }
    }

    @MainActor
    private func prunePreviewCache() {
        let validIds = Set(
            gridEntries.compactMap { entry -> UUID? in
                if case .connected(_, let instance) = entry {
                    return instance.id
                }
                return nil
            }
        )

        cardPreviewsByInstanceId = cardPreviewsByInstanceId.filter { validIds.contains($0.key) }
        previewLastLoadedAt = previewLastLoadedAt.filter { validIds.contains($0.key) }
        previewLoadingIds = previewLoadingIds.filter { validIds.contains($0) }
        previewErrorIds = previewErrorIds.filter { validIds.contains($0) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 0) {
                    LauncherHeader(title: localizer.t.tabMedia, icon: "play.fill", count: connectedMediaCount) {
                        showingMediaServiceOrder = true
                    }

                    if !hasDismissedTutorial {
                        tutorialSection
                            .padding(.bottom, 16)
                    }

                    TailscaleCard()

                    if gridEntries.isEmpty {
                        emptyStateSection
                    } else {
                        serviceGrid
                    }
                    LauncherFooter(count: connectedMediaCount)
                }
                .padding(.horizontal, 16)
            }
            .background(AppTheme.background)
            .navigationBarHidden(true)
            .navigationDestination(for: MediaServiceRoute.self) { route in
                mediaServiceDestination(for: route)
            }
            .sheet(item: $quickSetupType) { type in
                ServiceLoginView(serviceType: type)
            }
            .sheet(item: $showLogin) { type in
                ServiceLoginView(serviceType: type)
            }
            .sheet(item: $signInContext) { context in
                ServiceLoginView(serviceType: context.serviceType, existingInstanceId: context.instanceId)
            }
            .sheet(item: $proxySignIn) { target in
                ForwardAuthSheet(target: target) { access in
                    Task { await servicesStore.renewProxySession(target.instanceId, access: access) }
                }
            }
            .sheet(isPresented: $showingMediaServiceOrder) {
                MediaServiceOrderSheet()
            }
            .onChange(of: gridEntries.map(\.id)) { _, _ in
                prunePreviewCache()
            }
            .task {
                prunePreviewCache()
            }
        }
    }

    @ViewBuilder
    private func mediaServiceDestination(for route: MediaServiceRoute) -> some View {
        switch route.type {
        case .qbittorrent:
            QbittorrentDashboard(instanceId: route.instanceId)
        case .radarr:
            RadarrDashboard(instanceId: route.instanceId)
        case .sonarr:
            SonarrDashboard(instanceId: route.instanceId)
        case .lidarr:
            LidarrDashboard(instanceId: route.instanceId)
        case .jellyseerr, .prowlarr, .bazarr, .gluetun, .flaresolverr:
            GenericMediaDashboard(serviceType: route.type, instanceId: route.instanceId)
        default:
            EmptyView()
        }
    }

    private var emptyStateSection: some View {
        VStack(spacing: 16) {
            Image(systemName: "play.tv.fill")
                .font(.system(size: 48))
                .foregroundStyle(AppTheme.textMuted)
            
            Text(localizer.t.settingsNoInstances)
                .font(.headline)
            
            Text(localizer.t.settingsAddInstance)
                .font(.subheadline)
                .foregroundStyle(AppTheme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity)
        .glassCard()
        .padding(.top, 40)
    }

    private var tutorialSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "sparkles.rectangle.stack")
                    .foregroundStyle(AppTheme.info)
                Text(arr.tutorialTitle)
                    .font(.headline.bold())
                Spacer()
            }

            Text(arr.tutorialBody)
                .font(.subheadline)
                .foregroundStyle(AppTheme.textSecondary)

            VStack(alignment: .leading, spacing: 8) {
                tutorialRow(icon: "1.circle.fill", text: arr.tutorialStepConnect)
                tutorialRow(icon: "2.circle.fill", text: arr.tutorialStepOpen)
                tutorialRow(icon: "3.circle.fill", text: arr.tutorialStepAutomations)
            }

            HStack(spacing: 10) {
                if let firstUnconfigured = unconfiguredMediaServices.first {
                    Button {
                        quickSetupType = firstUnconfigured
                    } label: {
                        Label(arr.tutorialActionConfigure, systemImage: "plus")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppTheme.info)
                }

                Button {
                    hasDismissedTutorial = true
                } label: {
                    Text(arr.tutorialActionDismiss)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            .font(.subheadline.weight(.semibold))
        }
        .padding(16)
        .glassCard(tint: AppTheme.info.opacity(0.06))
    }

    private func tutorialRow(icon: String, text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: icon)
                .foregroundStyle(AppTheme.info)
            Text(text)
                .font(.caption)
                .foregroundStyle(AppTheme.textSecondary)
        }
    }

    private var serviceGrid: some View {
        GlassGroup(spacing: 16) {
            LazyVGrid(columns: columns, spacing: 14) {
                ForEach(gridEntries) { entry in
                    switch entry {
                    case .disconnected(let type):
                        Button {
                            HapticManager.medium()
                            showLogin = type
                        } label: {
                            ServiceCardContent(
                                type: type,
                                label: type.displayName,
                                isConnected: false,
                                isPreferred: false,
                                reachable: nil,
                                isPinging: false,
                                summary: nil,
                                isSummaryLoading: false,
                                t: localizer.t
                            )
                        }
                        .buttonStyle(.plain)
                    case .connected(let type, let instance):
                        connectedCard(type, instance)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func connectedCard(_ type: ServiceType, _ instance: ServiceInstance) -> some View {
        let preview = cardPreviewsByInstanceId[instance.id]
        let reachable = servicesStore.reachability(for: instance.id)
        let card = ServiceCardContent(
            type: type,
            label: instance.displayLabel,
            isConnected: true,
            isPreferred: servicesStore.preferredInstance(for: type)?.id == instance.id,
            reachable: reachable,
            needsSignIn: servicesStore.needsSignIn(instance.id),
            isPinging: servicesStore.isPinging(instanceId: instance.id),
            summary: preview?.summary,
            detail: reachable == true ? preview?.detail : nil,
            isSummaryLoading: preview == nil && previewLoadingIds.contains(instance.id),
            t: localizer.t
        ) {
            Task { await servicesStore.checkReachability(for: instance.id) }
        }
        Group {
            if servicesStore.needsSignIn(instance.id) {
                Button {
                    if let url = servicesStore.expiredProxyURL(instance.id),
                       let target = ForwardAuthTarget(renewing: url, instance: instance) {
                        proxySignIn = target
                    } else {
                        signInContext = ServiceEditorContext(serviceType: type, instanceId: instance.id)
                    }
                } label: {
                    card
                }
            } else {
                NavigationLink(value: MediaServiceRoute(type: type, instanceId: instance.id)) {
                    card
                }
            }
        }
        .buttonStyle(.plain)
        .task(id: reachable) {
            if reachable != false { await requestCardPreview(for: instance) }
        }
    }
}

private enum MediaGridEntry: Identifiable {
    case disconnected(type: ServiceType)
    case connected(type: ServiceType, instance: ServiceInstance)

    var id: String {
        switch self {
        case .disconnected(let type):
            return "disconnected-\(type.rawValue)"
        case .connected(_, let instance):
            return "connected-\(instance.id.uuidString)"
        }
    }
}

private struct MediaServiceRoute: Hashable {
    let type: ServiceType
    let instanceId: UUID
}

private struct MediaCardPreviewMetric: Identifiable, Equatable {
    let label: String
    let value: String

    var id: String { "\(label)-\(value)" }
}

private struct MediaCardPreviewData: Equatable {
    let headline: String?
    let metrics: [MediaCardPreviewMetric]

    /// The first value is shown like on the Home tab, the rest on one line below the name.
    var summary: ServiceSummaryInfo? {
        metrics.first.map { ServiceSummaryInfo(value: $0.value, label: $0.label) }
    }

    var detail: String {
        let rest = metrics.dropFirst().map { "\($0.label) \($0.value)" }
        return rest.isEmpty ? headline ?? "" : rest.joined(separator: " • ")
    }
}

private struct MediaServiceOrderSheet: View {
    @Environment(SettingsStore.self) private var settingsStore
    @Environment(Localizer.self) private var localizer
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(settingsStore.serviceOrder.filter { ServiceType.mediaServices.contains($0) }) { type in
                    let isHidden = settingsStore.isServiceHidden(type)
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(type.displayName)
                                .font(.body.weight(.semibold))
                            if isHidden {
                                Text(localizer.t.settingsHiddenBadge)
                                    .font(.caption2.bold())
                                    .foregroundStyle(.secondary)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(Color.secondary.opacity(0.12), in: Capsule())
                            }
                        }
                        Spacer()
                        HStack(spacing: 12) {
                            Button {
                                settingsStore.toggleServiceVisibility(type)
                                HapticManager.light()
                            } label: {
                                Image(systemName: isHidden ? "eye.slash" : "eye")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(isHidden ? localizer.t.settingsShowServiceGeneric : localizer.t.settingsHideServiceGeneric)

                            Button {
                                settingsStore.moveService(type, offset: -1, within: ServiceType.mediaServices)
                                HapticManager.light()
                            } label: {
                                Image(systemName: "chevron.up")
                            }
                            .buttonStyle(.borderless)
                            .disabled(!settingsStore.canMoveService(type, offset: -1, within: ServiceType.mediaServices))
                            .accessibilityLabel(localizer.t.settingsMoveUp)

                            Button {
                                settingsStore.moveService(type, offset: 1, within: ServiceType.mediaServices)
                                HapticManager.light()
                            } label: {
                                Image(systemName: "chevron.down")
                            }
                            .buttonStyle(.borderless)
                            .disabled(!settingsStore.canMoveService(type, offset: 1, within: ServiceType.mediaServices))
                            .accessibilityLabel(localizer.t.settingsMoveDown)
                        }
                    }
                }
            }
            .navigationTitle(localizer.t.homeReorderServices)
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AppTheme.background)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(localizer.t.done) { dismiss() }
                }
            }
        }
    }
}
