.PHONY: ios android build

# Simulator build of the iOS app (regenerates the Xcode project first).
ios:
	cd ios && xcodegen generate && \
	  xcodebuild -project Canton.xcodeproj -scheme CantonWallet -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO && \
	  xcodebuild -project Canton.xcodeproj -scheme CantonDapp -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO

# Debug + R8 release build of the Android app.
android:
	cd android && ./gradlew :wallet-app:assembleDebug :wallet-app:assembleRelease :dapp-app:assembleDebug :dapp-app:assembleRelease

build: ios android
