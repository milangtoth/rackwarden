import SwiftUI

struct DockgeDashboard: View {
    let instanceId: UUID

    @Environment(ServicesStore.self) private var servicesStore
    @Environment(Localizer.self) private var localizer

    @State private var selectedInstanceId: UUID
    @State private var overview: DockgeOverview?
    @State private var state: LoadableState<Void> = .idle
    @State private var selectedStack: DockgeStack?

    private let accent = ServiceType.dockge.colors.primary

    init(instanceId: UUID) {
        self.instanceId = instanceId
        _selectedInstanceId = State(initialValue: instanceId)
    }

    var body: some View {
        ServiceDashboardLayout(
            serviceType: .dockge,
            instanceId: selectedInstanceId,
            state: state,
            onRefresh: fetch
        ) {
            instancePicker
            if let overview {
                LazyVGrid(columns: twoColumnGrid, spacing: AppTheme.gridSpacing) {
                    GlassStatCard(
                        title: localizer.t.dockgeRunningStacks,
                        value: "\(overview.runningCount)/\(overview.stacks.count)",
                        icon: "square.stack.3d.up.fill",
                        iconColor: AppTheme.running
                    )
                    GlassStatCard(
                        title: localizer.t.dockgeStatusExited,
                        value: "\(overview.stacks.count { $0.status == .exited })",
                        icon: "exclamationmark.triangle.fill",
                        iconColor: overview.stacks.contains { $0.status == .exited } ? AppTheme.danger : AppTheme.textMuted
                    )
                }
                ForEach(overview.offlineAgents, id: \.self) { agent in
                    Label(String(format: localizer.t.dockgeAgentOffline, agent), systemImage: "wifi.slash")
                        .font(.subheadline)
                        .foregroundStyle(AppTheme.warning)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .glassCard()
                }
                if overview.stacks.isEmpty {
                    Text(localizer.t.dockgeNoStacks)
                        .font(.subheadline)
                        .foregroundStyle(AppTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .glassCard()
                }
                ForEach(overview.endpoints, id: \.self) { endpoint in
                    Text(endpoint.isEmpty ? localizer.t.dockgeStacks : endpoint)
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    ForEach(overview.stacks.filter { $0.endpoint == endpoint }) { stack in
                        Button {
                            HapticManager.light()
                            selectedStack = stack
                        } label: {
                            row(stack)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .navigationTitle(ServiceType.dockge.displayName)
        .sheet(item: $selectedStack) { stack in
            DockgeStackView(instanceId: selectedInstanceId, stack: stack, onChange: fetch)
        }
        .task(id: selectedInstanceId) { await fetch() }
    }

    @ViewBuilder
    private var instancePicker: some View {
        let instances = servicesStore.instances(for: .dockge)
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
                        servicesStore.setPreferredInstance(id: instance.id, for: .dockge)
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

    private func row(_ stack: DockgeStack) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(stack.status.color)
                .frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 3) {
                Text(stack.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                if !stack.isManaged {
                    Text(localizer.t.dockgeNotManaged).font(.caption).foregroundStyle(AppTheme.textMuted)
                }
            }
            Spacer(minLength: 0)
            Text(stack.status.label(localizer.t))
                .font(.caption.weight(.semibold))
                .foregroundStyle(stack.status.color)
        }
        .padding(14)
        .glassCard()
        .contentShape(Rectangle())
    }

    private func fetch() async {
        let id = selectedInstanceId
        guard let client = await servicesStore.dockgeClient(instanceId: id) else {
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

// MARK: - Stack

private struct DockgeStackView: View {
    let instanceId: UUID
    let stack: DockgeStack
    let onChange: () async -> Void

    @Environment(ServicesStore.self) private var servicesStore
    @Environment(Localizer.self) private var localizer
    @Environment(\.dismiss) private var dismiss

    @State private var services: [DockgeService]?
    @State private var running: DockgeAction?
    @State private var confirming: DockgeAction?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent {
                        Text(stack.status.label(localizer.t)).foregroundStyle(stack.status.color)
                    } label: {
                        Text(stack.endpoint.isEmpty ? localizer.t.dockgeStacks : stack.endpoint)
                    }
                    if !stack.isManaged {
                        Text(localizer.t.dockgeNotManaged).foregroundStyle(AppTheme.textSecondary)
                    }
                }
                if let errorMessage {
                    Text(errorMessage).foregroundStyle(AppTheme.danger)
                }
                if stack.isManaged { actionSection }
                Section(localizer.t.dockgeServices) {
                    if let services {
                        if services.isEmpty {
                            Text(localizer.t.noData).foregroundStyle(AppTheme.textMuted)
                        }
                        ForEach(services) { service in
                            LabeledContent {
                                Text(service.state)
                                    .foregroundStyle(service.isRunning ? AppTheme.running : AppTheme.warning)
                            } label: {
                                Text(service.name)
                                if !service.ports.isEmpty {
                                    Text(service.ports.joined(separator: ", "))
                                }
                            }
                        }
                    } else if errorMessage == nil {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                }
            }
            .navigationTitle(stack.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(localizer.t.close) { dismiss() }
                }
            }
            .interactiveDismissDisabled(running != nil)
            .task { await loadServices() }
        }
    }

    private var actionSection: some View {
        Section {
            ForEach(available, id: \.self) { action in
                Button {
                    if action.needsConfirmation { confirming = action } else { perform(action) }
                } label: {
                    HStack {
                        Label(action.label(localizer.t), systemImage: action.icon)
                        Spacer()
                        if running == action { ProgressView() }
                    }
                }
                .disabled(running != nil)
            }
        } footer: {
            if running != nil { Text(localizer.t.dockgeWorking) }
        }
        .confirmationDialog(
            confirming == .stop
                ? String(format: localizer.t.dockgeStopConfirm, stack.name)
                : String(format: localizer.t.dockgeUpdateConfirm, stack.name),
            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
            titleVisibility: .visible,
            presenting: confirming
        ) { action in
            Button(action.label(localizer.t), role: action == .stop ? .destructive : nil) { perform(action) }
        }
    }

    private var available: [DockgeAction] {
        stack.status == .running ? [.restart, .update, .stop] : [.start, .update, .stop]
    }

    private func perform(_ action: DockgeAction) {
        running = action
        errorMessage = nil
        HapticManager.medium()
        Task {
            defer { running = nil }
            guard let client = await servicesStore.dockgeClient(instanceId: instanceId) else { return }
            do {
                try await client.perform(action, on: stack)
                HapticManager.success()
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
                HapticManager.error()
            }
            await onChange()
        }
    }

    private func loadServices() async {
        guard let client = await servicesStore.dockgeClient(instanceId: instanceId) else { return }
        do {
            services = try await client.getServices(stack)
        } catch {
            guard !Task.isCancelled else { return }
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Presentation

private extension DockgeStack.Status {
    var color: Color {
        switch self {
        case .running: AppTheme.running
        case .exited: AppTheme.danger
        case .draft, .created, .unknown: AppTheme.textMuted
        }
    }

    @MainActor func label(_ t: TranslationCatalog) -> String {
        switch self {
        case .running: t.dockgeStatusRunning
        case .exited: t.dockgeStatusExited
        case .draft, .created: t.dockgeStatusInactive
        case .unknown: t.dockgeStatusUnknown
        }
    }
}

private extension DockgeAction {
    var needsConfirmation: Bool { self == .stop || self == .update }

    var icon: String {
        switch self {
        case .start: "play.fill"
        case .stop: "stop.fill"
        case .restart: "arrow.clockwise"
        case .update: "arrow.down.circle"
        }
    }

    @MainActor func label(_ t: TranslationCatalog) -> String {
        switch self {
        case .start: t.dockgeStart
        case .stop: t.dockgeStop
        case .restart: t.dockgeRestart
        case .update: t.dockgeUpdate
        }
    }
}
