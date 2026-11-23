import SwiftUI

/// Bridges the code prompt to the login that is waiting for it.
@MainActor
final class TwoFactorPrompt: Identifiable {
    let id = UUID()
    let challenge: TwoFactorChallenge
    let resend: ((TwoFactorChallenge) async throws -> TwoFactorChallenge)?
    let verify: (TwoFactorChallenge, String) async throws -> String
    private var continuation: CheckedContinuation<String, Error>?

    init(
        challenge: TwoFactorChallenge,
        resend: ((TwoFactorChallenge) async throws -> TwoFactorChallenge)?,
        verify: @escaping (TwoFactorChallenge, String) async throws -> String,
        continuation: CheckedContinuation<String, Error>
    ) {
        self.challenge = challenge
        self.resend = resend
        self.verify = verify
        self.continuation = continuation
    }

    /// Safe to call more than once; only the first result counts.
    func finish(_ result: Result<String, Error>) {
        continuation?.resume(with: result)
        continuation = nil
    }
}

struct TwoFactorSheet: View {
    let prompt: TwoFactorPrompt

    @Environment(Localizer.self) private var localizer
    @Environment(\.dismiss) private var dismiss
    @State private var challenge: TwoFactorChallenge
    @State private var code = ""
    @State private var isWorking = false
    @State private var errorMessage: String?
    @FocusState private var isFocused: Bool

    init(prompt: TwoFactorPrompt) {
        self.prompt = prompt
        _challenge = State(initialValue: prompt.challenge)
    }

    private var isEmail: Bool {
        if case .email = challenge.kind { return true }
        return false
    }

    private var trimmedCode: String {
        code.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(localizer.t.twoFactorCode, text: $code)
                        .keyboardType(isEmail ? .numberPad : .asciiCapable)
                        .textContentType(.oneTimeCode)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.title3.monospaced())
                        .focused($isFocused)
                        .onSubmit(submit)
                } header: {
                    Text(isEmail ? localizer.t.twoFactorEmailMessage : localizer.t.twoFactorAppMessage)
                        .textCase(nil)
                } footer: {
                    if let errorMessage {
                        Text(errorMessage).foregroundStyle(AppTheme.danger)
                    }
                }

                if prompt.resend != nil {
                    Button(localizer.t.twoFactorResend, action: resend)
                        .disabled(isWorking)
                }
            }
            .navigationTitle(localizer.t.twoFactorTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(localizer.t.cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isWorking {
                        ProgressView()
                    } else {
                        Button(localizer.t.twoFactorVerify, action: submit)
                            .disabled(trimmedCode.count < 6)
                    }
                }
            }
            .onAppear { isFocused = true }
        }
        .presentationDetents([.medium, .large])
        .onDisappear { prompt.finish(.failure(CancellationError())) }
    }

    private func submit() {
        guard !isWorking, trimmedCode.count >= 6 else { return }
        run {
            let token = try await prompt.verify(challenge, trimmedCode)
            prompt.finish(.success(token))
            dismiss()
        }
    }

    private func resend() {
        guard !isWorking, let resend = prompt.resend else { return }
        run {
            challenge = try await resend(challenge)
            code = ""
        }
    }

    private func run(_ action: @escaping () async throws -> Void) {
        errorMessage = nil
        isWorking = true
        Task {
            do {
                try await action()
            } catch {
                code = ""
                errorMessage = error.localizedDescription
                HapticManager.error()
            }
            isWorking = false
        }
    }
}
