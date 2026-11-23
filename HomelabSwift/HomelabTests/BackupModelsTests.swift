import XCTest
@testable import Homelab

final class BackupModelsTests: XCTestCase {
    func testBackupMapperRoundTripCoversEverySupportedService() {
        ServiceType.allCases.forEach { type in
            let key = BackupServiceTypeMapper.backupKey(for: type)
            XCTAssertEqual(
                BackupServiceTypeMapper.serviceType(from: key),
                type,
                "Missing backup round-trip for \(type.rawValue)"
            )
        }
    }

    func testPangolinBackupMapperRoundTrip() {
        XCTAssertEqual(BackupServiceTypeMapper.backupKey(for: .pangolin), "pangolin")
        XCTAssertEqual(BackupServiceTypeMapper.serviceType(from: "pangolin"), .pangolin)

        let entry = BackupServiceEntry(
            type: "pangolin",
            label: "Pangolin Edge",
            url: "https://pangolin.local",
            token: nil,
            username: nil,
            apiKey: "pangolin-key",
            piholePassword: nil,
            piholeAuthMode: nil,
            proxmoxAuthMode: nil,
            proxmoxRealm: nil,
            unifiAuthMode: nil,
            fallbackUrl: "https://pangolin.example.com",
            allowSelfSigned: false,
            password: nil,
            isPreferred: true
        )

        let instance = entry.toServiceInstance()
        XCTAssertEqual(instance?.type, .pangolin)
        XCTAssertEqual(instance?.apiKey, "pangolin-key")
        XCTAssertEqual(instance?.fallbackUrl, "https://pangolin.example.com")
    }

    func testTrueNASBackupMapperAliases() {
        XCTAssertEqual(BackupServiceTypeMapper.backupKey(for: .truenas), "truenas")
        XCTAssertEqual(BackupServiceTypeMapper.serviceType(from: "truenas"), .truenas)
        XCTAssertEqual(BackupServiceTypeMapper.serviceType(from: "truenas_scale"), .truenas)
        XCTAssertEqual(BackupServiceTypeMapper.serviceType(from: "truenas-scale"), .truenas)
        XCTAssertEqual(BackupServiceTypeMapper.serviceType(from: "truenas_core"), .truenas)
        XCTAssertEqual(BackupServiceTypeMapper.serviceType(from: "truenas-core"), .truenas)
    }

    // MARK: - Compatibility with 1.6.2

    /// The entry exactly as 1.6.2 reads and writes it.
    private struct LegacyEntry: Codable {
        let type: String
        let label: String
        let url: String
        let token: String?
        let username: String?
        let apiKey: String?
        let piholePassword: String?
        let piholeAuthMode: String?
        let proxmoxAuthMode: String?
        let proxmoxRealm: String?
        let unifiAuthMode: String?
        let fallbackUrl: String?
        let allowSelfSigned: Bool
        let password: String?
        let isPreferred: Bool
    }

    private struct LegacyEnvelope: Codable {
        let version: Int
        let exportedAt: String
        let appVersion: String
        let services: [LegacyEntry]
    }

    @MainActor
    func testA162BackupFileIsImported() throws {
        let json = #"{"version":1,"exportedAt":"2026-05-06T10:00:00Z","appVersion":"1.6.2","services":[{"type":"beszel","label":"Beszel","url":"https://beszel.example.test","token":"t","username":"me@example.test","fallbackUrl":"http://10.0.0.2:8090","allowSelfSigned":true,"password":"p","isPreferred":true},{"type":"proxmox","label":"PVE","url":"https://pve.example.test","username":"root","proxmoxAuthMode":"credentials","proxmoxRealm":"pam","allowSelfSigned":true,"password":"p","isPreferred":true},{"type":"made_up","label":"?","url":"https://x.example.test","allowSelfSigned":false,"isPreferred":false}]}"#
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).homelab")
        try BackupCrypto.encrypt(data: Data(json.utf8), password: "secret").write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }

        let preview = try BackupManager(servicesStore: ServicesStore()).previewBackup(from: file, password: "secret")
        XCTAssertEqual(preview.knownServiceTypes, [.beszel, .proxmox])
        XCTAssertEqual(preview.unknownServiceTypes, ["made_up"])

        let beszel = try XCTUnwrap(preview.knownServices.first?.toServiceInstance())
        XCTAssertEqual(beszel.username, "me@example.test")
        XCTAssertEqual(beszel.password, "p")
        XCTAssertEqual(beszel.fallbackUrl, "http://10.0.0.2:8090")
        XCTAssertNil(preview.knownServices.first?.accessProfile)
        XCTAssertEqual(preview.knownServices.last?.toServiceInstance()?.proxmoxRealm, "pam")
    }

    func testNewBackupsAreReadableBy162WithoutSessions() throws {
        let profile = AccessProfile(isEnabled: true, origins: [OriginAccess(
            origin: AccessOrigin(string: "https://dockge.example.test")!,
            headers: [AccessHeader(name: "X-Proxy", value: "h")],
            cookies: [AccessCookie(name: "session", value: "cookie-value", path: "/", expiresAt: nil)]
        )])
        let instances = [
            ServiceInstance(type: .dockge, label: "Dockge", url: "https://dockge.example.test", token: "t"),
            ServiceInstance(type: .scrutiny, label: "Scrutiny", url: "https://scrutiny.example.test"),
            ServiceInstance(type: .beszel, label: "Beszel", url: "https://beszel.example.test", token: "t")
        ]
        let data = try JSONEncoder().encode(BackupEnvelope(
            version: BackupEnvelope.currentVersion, exportedAt: "2026-11-23T10:00:00Z", appVersion: "2.0.0",
            services: instances.map { $0.toBackupEntry(isPreferred: true, accessProfile: $0.type == .dockge ? profile : nil) }
        ))
        let legacy = try JSONDecoder().decode(LegacyEnvelope.self, from: data)
        XCTAssertEqual(legacy.version, 1)
        XCTAssertEqual(legacy.services.map(\.type), ["dockge", "scrutiny", "beszel"])

        let text = String(decoding: data, as: UTF8.self)
        XCTAssertFalse(text.contains("cookie-value"))
        XCTAssertTrue(text.contains("X-Proxy"))
    }
}
