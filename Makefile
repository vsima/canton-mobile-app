.PHONY: ios android build

# Simulator build of the iOS app (regenerates the Xcode project first).
ios:
	cd ios && xcodegen generate && xcodebuild -project CantonApp.xcodeproj -scheme CantonApp -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO

# Debug + R8 release build of the Android app.
android:
	cd android && ./gradlew :app:assembleDebug :app:assembleRelease

build: ios android
