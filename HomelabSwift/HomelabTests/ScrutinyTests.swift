import XCTest
@testable import Homelab

final class ScrutinyTests: XCTestCase {
    private let uuid = "11111111-2222-3333-4444-555555555555"

    private func summary(_ id: String, device: String, smart: String? = #"{"collector_date":"2026-01-02T03:04:05.123456789Z","temp":34,"power_on_hours":48}"#) -> String {
        #"{"success":true,"data":{"summary":{"\#(id)":{"device":{\#(device)}\#(smart.map { #","smart":\#($0)"# } ?? "")}}}}"#
    }

    private func server(_ routes: [String: (Int, String)]) async throws -> (MockServer, ScrutinyAPIClient) {
        let server = try await MockServer { routes[$0.query.isEmpty ? $0.route : "\($0.route)?\($0.query)"] ?? (404, #"{"success":false}"#) }
        let client = ScrutinyAPIClient(instanceId: UUID())
        await client.configure(url: server.url + "/web/dashboard/", allowSelfSigned: false)
        return (server, client)
    }

    func testDrivesFromCurrentAndOlderServers() async throws {
        for (id, device, archivable) in [
            (uuid, #""scrutiny_uuid":"\#(uuid)","device_name":"sda","device_status":0,"archived":false"#, true),
            ("0x5000c500673e6b5f", #""wwn":"0x5000c500673e6b5f","device_name":"sda","device_status":0"#, false)
        ] {
            let (mock, client) = try await server(["GET /api/summary": (200, summary(id, device: device))])
            defer { mock.stop() }
            let overview = try await client.getOverview()
            let drive = try XCTUnwrap(overview.drives.first)
            XCTAssertEqual(drive.id, id)
            XCTAssertEqual(drive.device.title, "/dev/sda")
            XCTAssertEqual(drive.temperature, 34)
            XCTAssertEqual(drive.canArchive, archivable)
            XCTAssertNotNil(drive.lastSeen)
            XCTAssertEqual(overview.healthyCount, 1)
        }
    }

    func testStatusFollowsTheServersThreshold() async throws {
        // Failed by Scrutiny only.
        let body = summary(uuid, device: #""device_name":"sda","device_status":2"#)
        for (threshold, failed) in [(1, 0), (2, 1), (3, 1)] {
            let (mock, client) = try await server([
                "GET /api/summary": (200, body),
                "GET /api/settings": (200, #"{"success":true,"settings":{"temperature_unit":"fahrenheit","metrics":{"status_threshold":\#(threshold)}}}"#)
            ])
            defer { mock.stop() }
            let overview = try await client.getOverview()
            XCTAssertEqual(overview.failedCount, failed)
            XCTAssertEqual(overview.settings.format(100), "212 °F")
        }
    }

    func testADriveWithoutDataIsNotCalledHealthy() async throws {
        let (mock, client) = try await server(["GET /api/summary": (200, summary(uuid, device: #""device_name":"sda","device_status":0"#, smart: nil))])
        defer { mock.stop() }
        let overview = try await client.getOverview()
        XCTAssertEqual(overview.healthyCount, 0)
        XCTAssertEqual(overview.drives.first.map(overview.health), .unknown)
    }

    func testAttributeLevels() {
        func level(_ status: Int, _ threshold: Int) -> ScrutinyAttribute.Level {
            ScrutinyAttribute(id: "1", name: "", value: "", isCritical: false, reason: nil, status: status).level(threshold: threshold)
        }
        XCTAssertEqual(level(0, 3), .passed)
        XCTAssertEqual(level(1, 3), .failed)
        XCTAssertEqual(level(2, 3), .warning)
        XCTAssertEqual(level(4, 3), .failed)
        XCTAssertEqual(level(4, 1), .passed)
        XCTAssertEqual(level(1, 2), .passed)
    }

    func testDetailsFallBackToTheFullHistory() async throws {
        let full = #"{"success":true,"data":{"device":{"device_name":"sda"},"smart_results":[{"date":"2026-01-02T03:04:05Z","power_cycle_count":9,"attrs":{"194":{"attribute_id":194,"value":66,"raw_value":34,"raw_string":"34 (Min/Max 1/2)","transformed_value":34,"status":0},"5":{"attribute_id":5,"value":100,"raw_value":7,"raw_string":"7","status":4,"status_reason":"too many"}}},{"date":"2025-01-01T00:00:00Z","attrs":{}}]},"metadata":{"194":{"display_name":"Temperature","display_type":"transformed","transform_value_unit":"°C"},"5":{"display_name":"Reallocated Sectors","critical":true,"display_type":"raw"}}}"#
        let (mock, client) = try await server([
            "GET /api/device/\(uuid)/details?duration_key=week": (200, #"{"success":true,"data":{"device":{"device_name":"sda"},"smart_results":null},"metadata":null}"#),
            "GET /api/device/\(uuid)/details?duration_key=forever": (200, full)
        ])
        defer { mock.stop() }
        let details = try await client.getDetails(uuid)
        XCTAssertEqual(details.attributes.map(\.name), ["Reallocated Sectors", "Temperature"])
        XCTAssertEqual(details.attributes.map(\.value), ["7", "34 °C"])
        XCTAssertEqual(details.attributes.first?.isCritical, true)
        XCTAssertEqual(details.powerCycles, 9)
    }

    func testTemperaturesAreForTheDrive() async throws {
        let (mock, client) = try await server(["GET /api/summary/temp?duration_key=week": (200, #"{"success":true,"data":{"temp_history":{"\#(uuid)":[{"date":"2026-01-02T00:00:00Z","temp":35},{"date":"2026-01-01T00:00:00Z","temp":33},{"date":"2026-01-03T00:00:00Z","temp":0}],"other":[]}}}"#)])
        defer { mock.stop() }
        let found = try await client.getTemperatures(uuid, duration: "week")
        XCTAssertEqual(found.map(\.celsius), [33, 35])
    }

    func testActions() async throws {
        let (mock, client) = try await server([
            "POST /api/device/\(uuid)/archive": (200, #"{"success":true}"#),
            "POST /api/device/\(uuid)/unarchive": (200, #"{"success":true}"#),
            "POST /api/health/notify": (500, #"{"success":false,"errors":["no notifier set up"]}"#)
        ])
        defer { mock.stop() }
        try await client.setArchived(true, id: uuid)
        try await client.setArchived(false, id: uuid)
        XCTAssertEqual(mock.count("POST /api/device/\(uuid)/archive"), 1)
        XCTAssertEqual(mock.count("POST /api/device/\(uuid)/unarchive"), 1)
        do {
            try await client.sendTestNotification()
            XCTFail("Expected a failure")
        } catch {
            XCTAssertEqual(error.localizedDescription, "no notifier set up")
        }
    }

    func testDates() {
        XCTAssertNil(ScrutinyDate.parse("0001-01-01T00:00:00Z"))
        XCTAssertNil(ScrutinyDate.parse("nonsense"))
        XCTAssertNotNil(ScrutinyDate.parse("2026-01-02T03:04:05.5-07:00"))
    }

    func testNewServicesSurviveABackup() {
        for type in [ServiceType.scrutiny, .dockge] {
            XCTAssertEqual(BackupServiceTypeMapper.serviceType(from: BackupServiceTypeMapper.backupKey(for: type)), type)
            XCTAssertTrue(ForwardAuth.canSkipNativeLogin(type))
        }
    }
}
