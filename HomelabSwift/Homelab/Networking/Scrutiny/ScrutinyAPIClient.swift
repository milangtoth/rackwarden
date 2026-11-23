import Foundation

// MARK: - Models

struct ScrutinyDevice: Decodable, Hashable, Sendable {
    let scrutinyUUID: String?
    let wwn: String?
    let deviceName: String?
    let modelName: String?
    let serialNumber: String?
    let firmware: String?
    let capacity: Int64?
    let deviceProtocol: String?
    let label: String?
    let hostId: String?
    let friendlyName: String?
    let deviceStatus: Int?
    /// Missing on servers that cannot archive drives (before 0.8.2).
    let archived: Bool?

    private enum CodingKeys: String, CodingKey {
        case wwn, firmware, capacity, label, archived
        case scrutinyUUID = "scrutiny_uuid"
        case deviceName = "device_name"
        case modelName = "model_name"
        case serialNumber = "serial_number"
        case deviceProtocol = "device_protocol"
        case hostId = "host_id"
        case friendlyName = "friendly_name"
        case deviceStatus = "device_status"
    }

    var title: String {
        for name in [friendlyName, label] {
            if let name, !name.isEmpty { return name }
        }
        if let deviceName, !deviceName.isEmpty { return deviceName.hasPrefix("/") ? deviceName : "/dev/\(deviceName)" }
        return modelName ?? wwn ?? "?"
    }
}

struct ScrutinyDrive: Identifiable, Hashable, Sendable {
    /// The key the server lists the drive under: a UUID since 0.9.0, the WWN before that.
    let id: String
    let device: ScrutinyDevice
    let temperature: Int?
    let powerOnHours: Int?
    let lastSeen: Date?

    var isArchived: Bool { device.archived == true }
    var canArchive: Bool { device.archived != nil && !id.isEmpty }
    var hasData: Bool { lastSeen != nil }

    enum Health: Hashable, Sendable { case passed, failed, unknown }

    /// `threshold` is the server's choice of which checks count: 1 SMART, 2 Scrutiny, 3 both.
    func health(threshold: Int) -> Health {
        guard hasData else { return .unknown }
        return (device.deviceStatus ?? 0) & threshold == 0 ? .passed : .failed
    }
}

struct ScrutinySettings: Hashable, Sendable {
    var statusThreshold = 3
    var usesFahrenheit = false

    func format(_ celsius: Int) -> String {
        usesFahrenheit ? "\(Int((Double(celsius) * 9 / 5 + 32).rounded())) °F" : "\(celsius) °C"
    }
}

struct ScrutinyTemperature: Hashable, Sendable {
    let date: Date
    let celsius: Int
}

struct ScrutinyAttribute: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let value: String
    let isCritical: Bool
    let reason: String?
    let status: Int

    enum Level: Int, Comparable, Sendable {
        case failed, warning, passed
        static func < (lhs: Level, rhs: Level) -> Bool { lhs.rawValue < rhs.rawValue }
    }

    /// Bits: 1 failed by SMART, 2 warning, 4 failed by Scrutiny.
    func level(threshold: Int) -> Level {
        if status & 1 != 0, threshold & 1 != 0 { return .failed }
        if status & 4 != 0, threshold & 2 != 0 { return .failed }
        if status & 2 != 0, threshold & 2 != 0 { return .warning }
        return .passed
    }
}

struct ScrutinyDriveDetails: Hashable, Sendable {
    let device: ScrutinyDevice
    let date: Date?
    let powerCycles: Int?
    let attributes: [ScrutinyAttribute]
}

struct ScrutinyOverview: Hashable, Sendable {
    let drives: [ScrutinyDrive]
    let settings: ScrutinySettings

    var active: [ScrutinyDrive] { drives.filter { !$0.isArchived } }
    var archived: [ScrutinyDrive] { drives.filter(\.isArchived) }
    var healthyCount: Int { active.count { health($0) == .passed } }
    var failedCount: Int { active.count { health($0) == .failed } }
    var hottest: Int? { active.compactMap(\.temperature).max() }

    func health(_ drive: ScrutinyDrive) -> ScrutinyDrive.Health {
        drive.health(threshold: settings.statusThreshold)
    }
}

// MARK: - API Client

actor ScrutinyAPIClient {
    private let instanceId: UUID
    private var engine: BaseNetworkEngine
    private var baseURL = ""
    private var fallbackURL = ""

    init(instanceId: UUID) {
        self.instanceId = instanceId
        engine = BaseNetworkEngine(serviceType: .scrutiny, instanceId: instanceId)
    }

    func configure(url: String, fallbackUrl: String? = nil, allowSelfSigned: Bool) {
        baseURL = Self.cleanURL(url)
        fallbackURL = Self.cleanURL(fallbackUrl ?? "")
        engine = BaseNetworkEngine(serviceType: .scrutiny, instanceId: instanceId, allowSelfSigned: allowSelfSigned)
    }

    func ping() async -> Bool {
        for base in [baseURL, fallbackURL] where !base.isEmpty {
            if await engine.pingURL("\(base)/api/health") { return true }
        }
        return false
    }

    // MARK: - Reading

    func getOverview() async throws -> ScrutinyOverview {
        let summary: SummaryResponse = try await get("/api/summary")
        // Only affects how results are shown, so a failure must not hide the drives.
        let settings: SettingsResponse? = try? await get("/api/settings")
        return ScrutinyOverview(drives: summary.drives, settings: settings?.value ?? ScrutinySettings())
    }

    func getDetails(_ id: String) async throws -> ScrutinyDriveDetails {
        // The full history can be years long; only the latest result is shown.
        let recent: DetailsResponse = try await get("/api/device/\(Self.escape(id))/details?duration_key=week")
        if recent.data.smart_results?.isEmpty == false { return recent.details }
        let all: DetailsResponse = try await get("/api/device/\(Self.escape(id))/details?duration_key=forever")
        return all.details
    }

    /// - Parameter duration: "week", "month", "year" or "forever".
    func getTemperatures(_ id: String, duration: String) async throws -> [ScrutinyTemperature] {
        let response: TemperatureResponse = try await get("/api/summary/temp?duration_key=\(duration)")
        return (response.data.temp_history?[id] ?? [])
            .compactMap { point in
                guard let date = ScrutinyDate.parse(point.date), point.temp > 0 else { return nil }
                return ScrutinyTemperature(date: date, celsius: point.temp)
            }
            .sorted { $0.date < $1.date }
    }

    // MARK: - Actions

    func setArchived(_ archived: Bool, id: String) async throws {
        try await post("/api/device/\(Self.escape(id))/\(archived ? "archive" : "unarchive")")
    }

    func sendTestNotification() async throws {
        try await post("/api/health/notify")
    }

    // MARK: - Helpers

    private func get<T: Decodable & Sendable>(_ path: String) async throws -> T {
        try await engine.request(
            baseURL: baseURL, fallbackURL: fallbackURL, path: path, headers: ["Accept": "application/json"]
        )
    }

    private func post(_ path: String) async throws {
        do {
            let reply: ActionResponse = try await engine.request(
                baseURL: baseURL, fallbackURL: fallbackURL, path: path, method: "POST",
                headers: ["Accept": "application/json", "Content-Type": "application/json"]
            )
            guard reply.success else { throw APIError.custom(Self.message(reply.errors)) }
        } catch {
            var cause = error
            if case APIError.bothURLsFailed(let primary, _) = error { cause = primary }
            if case APIError.httpError(_, let body) = cause,
               let reply = try? JSONDecoder().decode(ActionResponse.self, from: Data(body.utf8)) {
                throw APIError.custom(Self.message(reply.errors))
            }
            throw error
        }
    }

    private static func message(_ errors: [String]?) -> String {
        let text = (errors ?? []).joined(separator: "\n")
        return text.isEmpty ? Translations.current().scrutinyActionFailed : text
    }

    private static func escape(_ id: String) -> String {
        id.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(.init(charactersIn: "-"))) ?? id
    }

    private static func cleanURL(_ url: String) -> String {
        var clean = url.trimmingCharacters(in: .whitespacesAndNewlines)
        // People often copy the address of the web page.
        for suffix in ["/web/dashboard", "/web"] {
            while clean.hasSuffix("/") { clean.removeLast() }
            if clean.hasSuffix(suffix) { clean.removeLast(suffix.count) }
        }
        while clean.hasSuffix("/") { clean.removeLast() }
        return clean
    }
}

// MARK: - Responses

enum ScrutinyDate {
    /// Go writes up to nine decimals, and year 1 when there is no date.
    static func parse(_ text: String?) -> Date? {
        guard let text else { return nil }
        let whole = text.replacingOccurrences(of: #"\.\d+"#, with: "", options: .regularExpression)
        guard let date = ISO8601DateFormatter().date(from: whole), date.timeIntervalSince1970 > 0 else { return nil }
        return date
    }
}

struct SummaryResponse: Decodable {
    struct Payload: Decodable { let summary: [String: Entry] }
    struct Entry: Decodable { let device: ScrutinyDevice; let smart: Smart? }
    struct Smart: Decodable { let collector_date: String?; let temp: Int?; let power_on_hours: Int? }

    let data: Payload

    var drives: [ScrutinyDrive] {
        data.summary.map { id, entry in
            ScrutinyDrive(
                id: id,
                device: entry.device,
                temperature: entry.smart?.temp.flatMap { $0 > 0 ? $0 : nil },
                powerOnHours: entry.smart?.power_on_hours,
                lastSeen: ScrutinyDate.parse(entry.smart?.collector_date)
            )
        }
        .sorted {
            let left = ($0.device.hostId ?? "", $0.device.title, $0.id)
            let right = ($1.device.hostId ?? "", $1.device.title, $1.id)
            return left < right
        }
    }
}

struct DetailsResponse: Decodable {
    struct Payload: Decodable { let device: ScrutinyDevice; let smart_results: [Result]? }
    struct Result: Decodable { let date: String?; let power_cycle_count: Int?; let attrs: [String: Attribute]? }
    struct Attribute: Decodable {
        let value: Int64?
        let raw_value: Int64?
        let raw_string: String?
        let transformed_value: Int64?
        let status: Int?
        let status_reason: String?
    }
    struct Metadata: Decodable {
        let display_name: String?
        let critical: Bool?
        let display_type: String?
        let display_transformed: Bool?
        let transform_value_unit: String?
    }

    let data: Payload
    let metadata: [String: Metadata]?

    var details: ScrutinyDriveDetails {
        // The server lists the newest result first.
        let latest = data.smart_results?.first
        let attributes = (latest?.attrs ?? [:]).map { id, attribute in
            let info = metadata?[id]
            return ScrutinyAttribute(
                id: id,
                name: info?.display_name.flatMap { $0.isEmpty ? nil : $0 } ?? id,
                value: Self.display(attribute, info),
                isCritical: info?.critical == true,
                reason: attribute.status_reason.flatMap { $0.isEmpty ? nil : $0 },
                status: attribute.status ?? 0
            )
        }
        return ScrutinyDriveDetails(
            device: data.device,
            date: ScrutinyDate.parse(latest?.date),
            powerCycles: latest?.power_cycle_count,
            attributes: attributes.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        )
    }

    private static func display(_ attribute: Attribute, _ info: Metadata?) -> String {
        if info?.display_type == "transformed" || info?.display_transformed == true {
            let value = attribute.transformed_value.map(String.init) ?? "–"
            return [value, info?.transform_value_unit ?? ""].filter { !$0.isEmpty }.joined(separator: " ")
        }
        if info?.display_type == "raw" {
            return attribute.raw_string ?? attribute.raw_value.map(String.init) ?? "–"
        }
        return attribute.value.map(String.init) ?? "–"
    }
}

struct TemperatureResponse: Decodable {
    struct Payload: Decodable { let temp_history: [String: [Point]]? }
    struct Point: Decodable { let date: String; let temp: Int }
    let data: Payload
}

struct SettingsResponse: Decodable {
    struct Settings: Decodable { let metrics: Metrics?; let temperature_unit: String? }
    struct Metrics: Decodable { let status_threshold: Int? }
    let settings: Settings?

    var value: ScrutinySettings {
        var result = ScrutinySettings()
        if let threshold = settings?.metrics?.status_threshold, (1...3).contains(threshold) {
            result.statusThreshold = threshold
        }
        result.usesFahrenheit = settings?.temperature_unit == "fahrenheit"
        return result
    }
}

private struct ActionResponse: Decodable {
    let success: Bool
    let errors: [String]?
}
