import Foundation

struct TrueNASSystemInfo: Equatable, Sendable {
    let version: String
    let hostname: String?
    let uptime: String?
    let systemProduct: String?
}

struct TrueNASPool: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let status: String
    let healthy: Bool
    let sizeBytes: Double
    let usedBytes: Double
    let availableBytes: Double

    var usedPercent: Double {
        guard sizeBytes > 0 else { return 0 }
        return min(max((usedBytes / sizeBytes) * 100, 0), 100)
    }
}

struct TrueNASDisk: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let model: String?
    let serial: String?
    let sizeBytes: Double
    let pool: String?
}

struct TrueNASAlert: Identifiable, Equatable, Sendable {
    let id: String
    let level: String
    let message: String
    let createdAt: String?
}

/// An alert's HTML as plain text, with numbered readings (as from a UPS) as label and value.
struct TrueNASAlertText: Equatable, Sendable {
    struct Reading: Hashable, Sendable {
        let label: String
        let value: String
    }

    let text: String
    let readings: [Reading]

    init(html: String) {
        let lines = Self.plain(html).components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
        var body: [String] = []
        var readings: [Reading] = []
        var index = 0
        while index < lines.count {
            // "1) Battery charge (percent)" followed by "battery.charge: 95"
            if let label = lines[index].wholeMatch(of: /\d+\) (.+)/)?.1,
               index + 1 < lines.count,
               let value = lines[index + 1].wholeMatch(of: /[\w.\-]+: (.+)/)?.1 {
                readings.append(Self.reading(String(label), String(value)))
                index += 2
            } else {
                body.append(lines[index])
                index += 1
            }
        }
        if !readings.isEmpty {
            body.removeAll { $0.hasPrefix("UPS Statistics:") || $0 == "Statistics recovered:" }
        }
        text = body.joined(separator: "\n")
            .replacingOccurrences(of: #"\n{3,}"#, with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        self.readings = readings
    }

    private static func reading(_ label: String, _ value: String) -> Reading {
        func without(_ unit: String) -> String {
            label.replacingOccurrences(of: " (\(unit))", with: "").trimmingCharacters(in: .whitespaces)
        }
        if label.contains("(percent)"), Double(value) != nil {
            return Reading(label: without("percent"), value: "\(value)%")
        }
        if label.contains("(seconds)"), let seconds = Int(value) {
            return Reading(
                label: without("seconds"),
                value: Duration.seconds(seconds).formatted(
                    .units(allowed: [.hours, .minutes, .seconds], width: .abbreviated, maximumUnitCount: 2)
                )
            )
        }
        return Reading(label: label, value: value)
    }

    private static func plain(_ html: String) -> String {
        var text = html.replacingOccurrences(of: "\r", with: "")
        for (pattern, with) in [
            (#"(?i)<br\s*/?>|</(p|div|li|tr|h[1-6])>"#, "\n"), (#"(?i)<li[^>]*>"#, "• "), (#"</?[a-zA-Z][^>]*>"#, "")
        ] {
            text = text.replacingOccurrences(of: pattern, with: with, options: .regularExpression)
        }
        // Last, so that "&amp;lt;" stays "&lt;".
        for (entity, character) in [
            ("&nbsp;", " "), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'"), ("&apos;", "'"), ("&amp;", "&")
        ] {
            text = text.replacingOccurrences(of: entity, with: character)
        }
        return text
    }
}

struct TrueNASServiceStatus: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let state: String
    let enabled: Bool
    let running: Bool
}

struct TrueNASShareSummary: Equatable, Sendable {
    let smbCount: Int
    let nfsCount: Int
    let iscsiCount: Int

    var totalCount: Int {
        smbCount + nfsCount + iscsiCount
    }
}

struct TrueNASWorkloadSummary: Equatable, Sendable {
    let appsTotal: Int
    let appsRunning: Int
    let virtualMachinesTotal: Int
    let virtualMachinesRunning: Int

    var hasWorkloads: Bool {
        appsTotal > 0 || virtualMachinesTotal > 0
    }
}

struct TrueNASDashboardSnapshot: Equatable, Sendable {
    let system: TrueNASSystemInfo
    let pools: [TrueNASPool]
    let disks: [TrueNASDisk]
    let alerts: [TrueNASAlert]
    let services: [TrueNASServiceStatus]
    let shareSummary: TrueNASShareSummary
    let workloadSummary: TrueNASWorkloadSummary

    var healthyPoolCount: Int {
        pools.filter(\.healthy).count
    }

    var totalStorageBytes: Double {
        pools.reduce(0) { $0 + $1.sizeBytes }
    }

    var usedStorageBytes: Double {
        pools.reduce(0) { $0 + $1.usedBytes }
    }

    var storageUsedPercent: Double {
        guard totalStorageBytes > 0 else { return 0 }
        return min(max((usedStorageBytes / totalStorageBytes) * 100, 0), 100)
    }
}
