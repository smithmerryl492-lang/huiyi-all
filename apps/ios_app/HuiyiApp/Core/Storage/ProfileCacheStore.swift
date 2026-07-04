import Foundation

final class ProfileCacheStore {
    private let cacheStore: ClientCacheStore

    init(cacheStore: ClientCacheStore = ClientCacheStore()) {
        self.cacheStore = cacheStore
    }

    func loadMembership(userId: String) -> MembershipProfile? {
        cacheStore.load(MembershipProfile.self, key: membershipKey(userId: userId))
    }

    func saveMembership(_ profile: MembershipProfile, userId: String) {
        try? cacheStore.save(profile, key: membershipKey(userId: userId))
    }

    func clearMembership(userId: String) {
        cacheStore.remove(key: membershipKey(userId: userId))
    }

    func loadSpeakerProfiles(userId: String) -> [SpeakerProfile] {
        cacheStore.load([SpeakerProfile].self, key: speakerProfilesKey(userId: userId)) ?? []
    }

    func saveSpeakerProfiles(_ profiles: [SpeakerProfile], userId: String) {
        try? cacheStore.save(profiles, key: speakerProfilesKey(userId: userId))
    }

    func clearSpeakerProfiles(userId: String) {
        cacheStore.remove(key: speakerProfilesKey(userId: userId))
    }

    private func membershipKey(userId: String) -> String {
        "membershipProfile.\(userId)"
    }

    private func speakerProfilesKey(userId: String) -> String {
        "speakerProfiles.\(userId)"
    }
}
