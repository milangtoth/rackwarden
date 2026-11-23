import Charts
import SwiftUI

struct ScrutinyDashboard: View {
    let instanceId: UUID

    @Environment(ServicesStore.self) private var servicesStore
    @Environment(Localizer.self) private var localizer

    @State private var selectedInstanceId: UUID
    @State private var overview: ScrutinyOverview?
    @State private var state: LoadableState<Void> = .idle
    @State private var selectedDrive: ScrutinyDrive?
    @State private var showArchived = false
    @State private var notice: String?
    @State private var isSending = false

    private let accent = ServiceType.scrutiny.colors.primary

    init(instanceId: UUID) {
        self.instanceId = instanceId
        _selectedInstanceId = State(initialValue: instanceId)
    }

    var body: some View {
        ServiceDashboardLayout(
            serviceType: .scrutiny,
            instanceId: selectedInstanceId,
            state: state,
            onRefresh: fetch
        ) {
            instancePicker
            if let overview {
                stats(overview)
                if overview.active.isEmpty {
                    Text(localizer.t.scrutinyNoDrives)
                        .font(.subheadline)
                        .foregroundStyle(AppTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .glassCard()
                }
                driveList(overview.active, overview: overview)
                archivedSection(overview)
            }
        }
        .navigationTitle(ServiceType.scrutiny.displayName)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button(localizer.t.scrutinyTestNotification, systemImage: "bell.badge", action: sendTest)
                        .disabled(isSending)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .sheet(item: $selectedDrive) { drive in
            ScrutinyDriveView(
                instanceId: selectedInstanceId,
                drive: drive,
                settings: overview?.settings ?? ScrutinySettings()
            ) {
                await fetch()
            }
        }
        .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
            Button(localizer.t.done) {}
        }
        .task(id: selectedInstanceId) { await fetch() }
    }

    @ViewBuilder
    private var instancePicker: some View {
        let instances = servicesStore.instances(for: .scrutiny)
        if instances.count > 1 {
            VStack(alignment: .leading, spacing: 12) {
                Text(localizer.t.dashboardInstances)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textMuted)
                    .textCase(.uppercase)
                ForEach(instances) { instance in
                    Button {
                        HapticManager.light()
                        overview = nil
                        selectedInstanceId = instance.id
                        servicesStore.setPreferredInstance(id: instance.id, for: .scrutiny)
                    } label: {
                        HStack(spacing: 10) {
                            Circle()
                                .fill(instance.id == selectedInstanceId ? accent : AppTheme.textMuted.opacity(0.4))
                                .frame(width: 10, height: 10)
                            Text(instance.displayLabel).font(.subheadline.weight(.semibold))
                            Spacer()
                        }
                        .padding(14)
                        .glassCard(tint: instance.id == selectedInstanceId ? accent.opacity(0.1) : nil)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func stats(_ overview: ScrutinyOverview) -> some View {
        LazyVGrid(columns: twoColumnGrid, spacing: AppTheme.gridSpacing) {
            GlassStatCard(
                title: localizer.t.scrutinyHealthyDrives,
                value: "\(overview.healthyCount)/\(overview.active.count)",
                icon: "checkmark.seal.fill",
                iconColor: AppTheme.running
            )
            GlassStatCard(
                title: localizer.t.scrutinyFailedDrives,
                value: "\(overview.failedCount)",
                icon: "exclamationmark.triangle.fill",
                iconColor: overview.failedCount == 0 ? AppTheme.textMuted : AppTheme.danger
            )
            if let hottest = overview.hottest {
                GlassStatCard(
                    title: localizer.t.scrutinyHottest,
                    value: overview.settings.format(hottest),
                    icon: "thermometer.medium",
                    iconColor: accent
                )
            }
        }
    }

    @ViewBuilder
    private func driveList(_ drives: [ScrutinyDrive], overview: ScrutinyOverview) -> some View {
        let hosts = Set(drives.map { $0.device.hostId ?? "" })
        ForEach(hosts.sorted(), id: \.self) { host in
            if hosts.count > 1 || !host.isEmpty {
                Text(host.isEmpty ? localizer.t.scrutinyDrives : host)
                    .font(.headline)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            ForEach(drives.filter { ($0.device.hostId ?? "") == host }) { drive in
                Button {
                    HapticManager.light()
                    selectedDrive = drive
                } label: {
                    row(drive, overview: overview)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func row(_ drive: ScrutinyDrive, overview: ScrutinyOverview) -> some View {
        let health = overview.health(drive)
        return HStack(spacing: 12) {
            Image(systemName: health.icon)
                .foregroundStyle(health.color)
                .frame(width: 34, height: 34)
                .background(health.color.opacity(0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .accessibilityLabel(health.label(localizer.t))
            VStack(alignment: .leading, spacing: 3) {
                Text(drive.device.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Text(subtitle(drive))
                    .font(.caption)
                    .foregroundStyle(AppTheme.textMuted)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
            if let temperature = drive.temperature {
                Text(overview.settings.format(temperature))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textSecondary)
            }
        }
        .padding(14)
        .glassCard(tint: health == .failed ? AppTheme.danger.opacity(0.06) : nil)
        .contentShape(Rectangle())
    }

    private func subtitle(_ drive: ScrutinyDrive) -> String {
        var parts: [String] = []
        if let model = drive.device.modelName, !model.isEmpty { parts.append(model) }
        if let capacity = drive.device.capacity, capacity > 0 { parts.append(ScrutinyFormat.capacity(capacity)) }
        if let hours = drive.powerOnHours { parts.append(ScrutinyFormat.hours(hours)) }
        return parts.joined(separator: " • ")
    }

    @ViewBuilder
    private func archivedSection(_ overview: ScrutinyOverview) -> some View {
        if !overview.archived.isEmpty {
            Button {
                withAnimation { showArchived.toggle() }
            } label: {
                HStack {
                    Text("\(localizer.t.scrutinyArchived) (\(overview.archived.count))").font(.headline)
                    Spacer()
                    Image(systemName: showArchived ? "chevron.up" : "chevron.down")
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if showArchived { driveList(overview.archived, overview: overview) }
        }
    }

    private func sendTest() {
        isSending = true
        Task {
            defer { isSending = false }
            guard let client = await servicesStore.scrutinyClient(instanceId: selectedInstanceId) else { return }
            do {
                try await client.sendTestNotification()
                notice = localizer.t.scrutinyTestSent
                HapticManager.success()
            } catch {
                notice = error.localizedDescription
                HapticManager.error()
            }
        }
    }

    private func fetch() async {
        let id = selectedInstanceId
        guard let client = await servicesStore.scrutinyClient(instanceId: id) else {
            state = .error(.notConfigured)
            return
        }
        if overview == nil { state = .loading }
        do {
            let loaded = try await client.getOverview()
            guard id == selectedInstanceId else { return }
            overview = loaded
            state = .loaded(())
        } catch {
            guard !Task.isCancelled, id == selectedInstanceId else { return }
            state = .error(error as? APIError ?? .networkError(error))
        }
    }
}

// MARK: - Drive

private struct ScrutinyDriveView: View {
    let instanceId: UUID
    let drive: ScrutinyDrive
    let settings: ScrutinySettings
    let onChange: () async -> Void

    @Environment(ServicesStore.self) private var servicesStore
    @Environment(Localizer.self) private var localizer
    @Environment(\.dismiss) private var dismiss

    @State private var details: ScrutinyDriveDetails?
    @State private var temperatures: [ScrutinyTemperature] = []
    @State private var duration = "week"
    @State private var errorMessage: String?
    @State private var showAllAttributes = false
    @State private var confirmsArchive = false
    @State private var isWorking = false

    private var health: ScrutinyDrive.Health { drive.health(threshold: settings.statusThreshold) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label(health.label(localizer.t), systemImage: health.icon)
                        .foregroundStyle(health.color)
                        .font(.headline)
                    info(localizer.t.scrutinyModel, drive.device.modelName)
                    info(localizer.t.scrutinySerial, drive.device.serialNumber)
                    info(localizer.t.scrutinyFirmware, drive.device.firmware)
                    info(localizer.t.scrutinyCapacity, drive.device.capacity.flatMap { $0 > 0 ? ScrutinyFormat.capacity($0) : nil })
                    info(localizer.t.scrutinyTemperature, drive.temperature.map(settings.format))
                    info(localizer.t.scrutinyPoweredOn, drive.powerOnHours.map(ScrutinyFormat.hours))
                    info(localizer.t.scrutinyPowerCycles, details?.powerCycles.map(String.init))
                    info(localizer.t.scrutinyLastReport, drive.lastSeen?.formatted(date: .abbreviated, time: .shortened))
                }
                if let errorMessage {
                    Text(errorMessage).foregroundStyle(AppTheme.danger)
                }
                chartSection
                attributeSection
                if drive.canArchive { archiveSection }
            }
            .navigationTitle(drive.device.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(localizer.t.close) { dismiss() }
                }
            }
            .task { await loadDetails() }
            .task(id: duration) { await loadTemperatures() }
        }
    }

    @ViewBuilder
    private func info(_ title: String, _ value: String?) -> some View {
        if let value, !value.isEmpty {
            LabeledContent(title, value: value)
        }
    }

    private var chartSection: some View {
        Section(localizer.t.scrutinyTemperature) {
            Picker(localizer.t.scrutinyTemperature, selection: $duration) {
                Text(localizer.t.scrutinyWeek).tag("week")
                Text(localizer.t.scrutinyMonth).tag("month")
                Text(localizer.t.scrutinyYear).tag("year")
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            if temperatures.count > 1 {
                Chart(temperatures, id: \.self) { point in
                    LineMark(
                        x: .value("Date", point.date),
                        y: .value("Temperature", settings.usesFahrenheit ? Double(point.celsius) * 9 / 5 + 32 : Double(point.celsius))
                    )
                    .foregroundStyle(ServiceType.scrutiny.colors.primary)
                }
                .chartYScale(domain: .automatic(includesZero: false))
                .frame(height: 160)
            } else {
                Text(localizer.t.noData).foregroundStyle(AppTheme.textMuted)
            }
        }
    }

    @ViewBuilder
    private var attributeSection: some View {
        if let details {
            let ranked = details.attributes.map { ($0, $0.level(threshold: settings.statusThreshold)) }
            let problems = ranked.filter { $0.1 != .passed }.sorted { $0.1 < $1.1 }
            let passed = ranked.filter { $0.1 == .passed }
            Section(localizer.t.scrutinyAttributes) {
                if problems.isEmpty, !passed.isEmpty {
                    Label(localizer.t.scrutinyAllPassed, systemImage: "checkmark.circle.fill")
                        .foregroundStyle(AppTheme.running)
                }
                if ranked.isEmpty {
                    Text(localizer.t.noData).foregroundStyle(AppTheme.textMuted)
                }
                ForEach(problems, id: \.0.id) { attributeRow($0.0, level: $0.1) }
                if !passed.isEmpty {
                    DisclosureGroup("\(localizer.t.scrutinyPassed) (\(passed.count))", isExpanded: $showAllAttributes) {
                        ForEach(passed, id: \.0.id) { attributeRow($0.0, level: $0.1) }
                    }
                }
            }
        } else if errorMessage == nil {
            ProgressView().frame(maxWidth: .infinity)
        }
    }

    private func attributeRow(_ attribute: ScrutinyAttribute, level: ScrutinyAttribute.Level) -> some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(attribute.name)
                if attribute.isCritical {
                    Text(localizer.t.scrutinyCritical).font(.caption2).foregroundStyle(AppTheme.textMuted)
                }
                if level != .passed, let reason = attribute.reason {
                    Text(reason).font(.caption).foregroundStyle(AppTheme.textSecondary)
                }
            }
            Spacer()
            Text(attribute.value).monospacedDigit().foregroundStyle(level.color)
        }
        .font(.subheadline)
    }

    private var archiveSection: some View {
        Section {
            Button(drive.isArchived ? localizer.t.scrutinyUnarchive : localizer.t.scrutinyArchive) {
                if drive.isArchived { setArchived(false) } else { confirmsArchive = true }
            }
            .disabled(isWorking)
            .confirmationDialog(
                localizer.t.scrutinyArchiveConfirm, isPresented: $confirmsArchive, titleVisibility: .visible
            ) {
                Button(localizer.t.scrutinyArchive) { setArchived(true) }
            }
        }
    }

    private func setArchived(_ archived: Bool) {
        isWorking = true
        Task {
            defer { isWorking = false }
            guard let client = await servicesStore.scrutinyClient(instanceId: instanceId) else { return }
            do {
                try await client.setArchived(archived, id: drive.id)
                HapticManager.success()
                dismiss()
                await onChange()
            } catch {
                errorMessage = error.localizedDescription
                HapticManager.error()
            }
        }
    }

    private func loadDetails() async {
        guard let client = await servicesStore.scrutinyClient(instanceId: instanceId) else { return }
        do {
            details = try await client.getDetails(drive.id)
        } catch {
            guard !Task.isCancelled else { return }
            errorMessage = error.localizedDescription
        }
    }

    private func loadTemperatures() async {
        let requested = duration
        guard let client = await servicesStore.scrutinyClient(instanceId: instanceId) else { return }
        let loaded = (try? await client.getTemperatures(drive.id, duration: requested)) ?? []
        if requested == duration, !Task.isCancelled { temperatures = loaded }
    }
}

// MARK: - Presentation

private enum ScrutinyFormat {
    /// Drives are sold in decimal units.
    static func capacity(_ bytes: Int64) -> String {
        bytes.formatted(.byteCount(style: .decimal))
    }

    static func hours(_ hours: Int) -> String {
        let duration = Duration.seconds(hours * 3600)
        return hours < 24
            ? duration.formatted(.units(allowed: [.hours], width: .abbreviated))
            : duration.formatted(.units(allowed: [.days], width: .abbreviated))
    }
}

private extension ScrutinyDrive.Health {
    var icon: String {
        switch self {
        case .passed: "checkmark.circle.fill"
        case .failed: "xmark.octagon.fill"
        case .unknown: "questionmark.circle.fill"
        }
    }

    var color: Color {
        switch self {
        case .passed: AppTheme.running
        case .failed: AppTheme.danger
        case .unknown: AppTheme.textMuted
        }
    }

    @MainActor func label(_ t: TranslationCatalog) -> String {
        switch self {
        case .passed: t.scrutinyPassed
        case .failed: t.scrutinyFailed
        case .unknown: t.scrutinyUnknown
        }
    }
}

private extension ScrutinyAttribute.Level {
    var color: Color {
        switch self {
        case .failed: AppTheme.danger
        case .warning: AppTheme.warning
        case .passed: AppTheme.textSecondary
        }
    }
}
