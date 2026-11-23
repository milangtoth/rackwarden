import Foundation

/// Keychain-backed access profiles keyed by instance ID. Drafts stay in memory
/// until the instance being set up is saved.
final class AccessProfileStore: @unchecked Sendable {
    static let shared = AccessProfileStore()

    private let lock = NSLock()
    private let load: @Sendable () -> [UUID: AccessProfile]
    private let save: @Sendable ([UUID: AccessProfile]) -> Void
    private var saved: [UUID: AccessProfile]?
    private var drafts: [UUID: AccessProfile] = [:]

    init(
        load: @escaping @Sendable () -> [UUID: AccessProfile] = { KeychainService.loadAccessProfiles() },
        save: @escaping @Sendable ([UUID: AccessProfile]) -> Void = { KeychainService.saveAccessProfiles($0) }
    ) {
        self.load = load
        self.save = save
    }

    func profile(for id: UUID) -> AccessProfile? {
        lock.withLock { drafts[id] ?? current()[id] }
    }

    func set(_ profile: AccessProfile?, for id: UUID) {
        lock.withLock { store(profile, for: id) }
    }

    func setDraft(_ profile: AccessProfile?, for id: UUID) {
        lock.withLock { drafts[id] = profile }
    }

    /// Starts a draft from an existing instance so edits and test logins do not affect it.
    func beginDraft(_ draftId: UUID, copying instanceId: UUID?) {
        lock.withLock {
            guard drafts[draftId] == nil, let instanceId else { return }
            drafts[draftId] = current()[instanceId]
        }
    }

    func commitDraft(_ draftId: UUID, to instance: ServiceInstance) {
        lock.withLock {
            // Without a draft, keep what is saved; turning access off is an explicit disabled draft.
            let profile = drafts.removeValue(forKey: draftId) ?? current()[instance.id]
            store(profile?.limited(to: [instance.url, instance.fallbackUrl]), for: instance.id)
        }
    }

    func discardDraft(_ draftId: UUID) {
        lock.withLock { drafts[draftId] = nil }
    }

    private func current() -> [UUID: AccessProfile] {
        if let saved { return saved }
        let loaded = load()
        saved = loaded
        return loaded
    }

    private func store(_ profile: AccessProfile?, for id: UUID) {
        var all = current()
        guard all[id] != profile else { return }
        all[id] = profile
        saved = all
        save(all)
    }
}
