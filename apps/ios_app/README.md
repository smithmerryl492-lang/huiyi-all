# 鲲穹会纪 iOS App

Native iOS source skeleton for 鲲穹会纪.

## Status

This directory contains the native SwiftUI iOS migration source for 鲲穹会纪. Development is still being prepared on Windows, so Swift compilation and runtime checks remain pending Mac/Xcode verification, but the repository now includes the XcodeGen project description, Info.plist, entitlements, and iOS asset catalog needed to generate the Xcode project on macOS.

Current source milestone:

- App entry, router, and session container
- XcodeGen project description in `project.yml`
- iOS `Info.plist`, minimal entitlements, app icon, and accent color assets
- Token storage boundary using Keychain
- URLSession API client for auth, membership, tasks, live session, knowledge, orders, and upload
- Codable models aligned with the existing FastAPI schemas
- Local task queue state machine that keeps failed/retry tasks visible
- Client-side file/cache/audio storage boundaries
- Recording, realtime ASR, file import, StoreKit, notifications, and audio playback service boundaries
- Login screen and session actions for password/SMS login
- Registration and password reset through existing auth APIs
- Main tab structure for meetings, knowledge, membership, and profile
- Meeting list bootstrap view using existing cloud sync API
- Meeting detail view for summary, topics, decisions, risks, todos, searchable transcript, source inspection, transcript correction, speaker editing, meeting info editing, and summary editing
- Todo completion, start, edit, delete, manual create, and source positioning through the existing task result update API
- Meeting delete and Markdown share
- Membership view for entitlements, plans, quota, add-ons, order history, and iOS purchase-disabled state
- Voiceprint profile list, rename, enable/disable, delete, import sample, and record sample using the existing voiceprint API
- File import flow with language selection, sandbox copy, and local queue creation
- Local import processing bridge: upload file first, then start remote task processing
- Recording view state machine: ASR ready before recording, cancel before start without task creation, pause/resume, schedule context, consent sheet, enqueue after stop
- Processing view that keeps failed/retry tasks visible, requires explicit retry, polls progress, and supports cancel
- Schedule list, schedule creation/edit/delete, conflict checks, app reminders, snooze/dismiss/start recording from reminder
- Knowledge Q&A with local/cloud/all scope switch, recommended questions, topic cards, source jump, cancel, retry edit, and context follow-up

Generate the Xcode project on macOS:

```bash
cd apps/ios_app
brew install xcodegen
xcodegen generate
open HuiyiApp.xcodeproj
```

See `MAC_VERIFICATION_CHECKLIST.md` for the exact Mac/Xcode verification list and items not implemented yet.

Not yet verified:

- Swift compilation
- SwiftUI runtime layout
- microphone recording
- document picker import
- StoreKit sandbox

## Technical Direction

- Swift + SwiftUI
- Current UI skeleton assumes iOS 16+ SwiftUI APIs. If product scope requires iOS 15, replace `NavigationStack`, `LabeledContent`, and multi-line `TextField` usage during Mac/Xcode integration.
- async/await
- URLSession
- URLSessionWebSocketTask
- AVAudioEngine / AVAudioSession for recording
- Keychain for auth tokens
- FileManager for local audio and imported files
- StoreKit 2 for future iOS purchases

## Backend Boundary

iOS reuses the existing `api_server` APIs for auth, membership display, meeting sync, task processing, knowledge Q&A, voiceprints, and order history.

The first iOS milestone must not change:

- Android package/signature
- Android Alipay WAP flow
- `/payments/alipay/...` semantics
- existing server database schema

Apple IAP will be added later through separate `/payments/apple/...` endpoints and Apple transaction storage.

## Mac Verification

Pending Mac/Xcode checks:

- Xcode project generation from `project.yml`
- Simulator launch
- Device microphone permission
- AVAudioEngine PCM conversion
- background audio behavior
- URLSessionWebSocketTask against Aliyun realtime ASR
- UIDocumentPicker security-scoped file access
- Keychain behavior
- StoreKit 2 sandbox
- TestFlight upload
